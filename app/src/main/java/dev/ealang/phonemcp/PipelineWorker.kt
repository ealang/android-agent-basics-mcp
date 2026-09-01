package dev.ealang.phonemcp

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * The one thread that mutates the queue past enqueue. Inbound surface:
 * [wake], from any thread — every reason to work (an enqueue, a Retry, a
 * network change, a TDLib event) raises the same flag. Outbound: one
 * callback per drain, with a report; the worker owns no retry timer and
 * does not know who listens. See `docs/webhook-pipeline.md` §4, §10.
 */
class PipelineWorker(
    private val store: RecordingStore,
    private val log: PipelineLog,
    private val noteWriter: NoteWriter,
    /** Stage 4 replaces the stub; the contract is fixed now. */
    private val telegramSender: TelegramSender,
    private val onDrainFinished: (DrainReport) -> Unit,
) {

    /** The Telegram destination, behind an interface so stage 1 can stub it. */
    interface TelegramSender {
        /** Hand one item's text to Telegram. Success means *handed off*, not delivered. */
        fun send(item: RecordingStore.Item, text: String): StepResult
    }

    private val thread = HandlerThread("webhook-pipeline")
    private lateinit var handler: Handler

    /** Collapses a burst of wakes into one queued drain. */
    @Volatile
    private var drainQueued = false

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun quit() {
        thread.quitSafely()
    }

    /** Thread-safe; call from anywhere. */
    fun wake() {
        if (drainQueued) return
        drainQueued = true
        handler.post {
            drainQueued = false
            onDrainFinished(drain())
        }
    }

    /** UI actions run on the worker too, so even `failed/` has one writer. */
    fun post(action: () -> Unit) {
        handler.post(action)
    }

    private fun drain(): DrainReport {
        var completed = 0
        var newlyFailed = 0
        var blockedOn: String? = null

        for (item in store.pending()) {
            // Handed off already: it waits for confirmation (stage 4's
            // reconcile), and does not block the items behind it.
            if (item.processorState(PROCESSOR_TELEGRAM) != null) continue

            val result = try {
                process(item)
            } catch (e: Exception) {
                // An unexpected exception is a failure report, never a dead
                // thread — and not knowing what it was, we must not assume
                // it will fix itself.
                Log.e(TAG, "Unexpected failure processing ${item.name}", e)
                StepResult.Permanent("unexpected: ${e::class.java.simpleName}")
            }

            when (result) {
                is StepResult.Success -> {
                    completed++
                    log.record(item.name, "completed")
                    store.complete(item)
                }
                is StepResult.Permanent -> {
                    newlyFailed++
                    log.record(item.name, "failed", result.why)
                    store.fail(item, result.why)
                }
                is StepResult.Transient -> {
                    // Ordering: nothing behind this item may pass it. Stop —
                    // don't sleep — and let the orchestrator decide when to
                    // wake us again.
                    log.record(item.name, "waiting", result.why)
                    blockedOn = "${item.name}: ${result.why}"
                    break
                }
            }
        }

        return DrainReport(completed, blockedOn, newlyFailed)
    }

    /**
     * Advance one item through its remaining steps. The destination is
     * resolved from config *now*, not at receipt — redefining the pipeline
     * redirects queued items (§3).
     */
    private fun process(item: RecordingStore.Item): StepResult {
        // Stage 2 inserts the transcription step here. Until then the text
        // is Pebble's transcript or nothing.
        val text = item.transcript() ?: item.meta.pebbleTranscript
            ?: return StepResult.Transient(
                "audio-only recording; transcription arrives in stage 2",
            )

        if (text.isBlank()) {
            // skip_empty: a pocket press or silence. Completing it quietly
            // is the designed outcome, not an error.
            return StepResult.Success
        }

        val recordedAt = item.meta.recordedAtMs.takeIf { it > 0 } ?: item.meta.receivedAtMs

        return when (WebhookConfig.destinationFor(item.meta.trigger)) {
            Destination.NOTE -> noteWriter.write(text, recordedAt)
            Destination.TELEGRAM -> telegramSender.send(item, text)
        }
    }

    companion object {
        private const val TAG = "phone-mcp"
        const val PROCESSOR_TELEGRAM = "telegram"
    }
}

/** Stage 1's Telegram: honestly not configured yet. Items wait, correctly. */
class StubTelegramSender : PipelineWorker.TelegramSender {
    override fun send(item: RecordingStore.Item, text: String): StepResult =
        StepResult.Transient("Telegram delivery is not built yet")
}
