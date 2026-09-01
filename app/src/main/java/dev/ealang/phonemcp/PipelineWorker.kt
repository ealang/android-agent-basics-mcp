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
    private val transcriber: Transcriber,
    /** Stage 4 replaces the stub; the contract is fixed now. */
    private val telegramSender: TelegramSender,
    private val onDrainFinished: (DrainReport) -> Unit,
) {

    /** The Telegram destination, behind an interface so stage 1 can stub it. */
    interface TelegramSender {
        /** Hand one item's text to Telegram. Success means *handed off*, not delivered. */
        fun send(item: RecordingStore.Item, text: String): StepResult
    }

    /** The transcription step, behind an interface so tests need no network. */
    interface Transcriber {
        /** Blocking. May take minutes; the worker's thread is for exactly this. */
        fun transcribe(audio: java.io.File): TranscribeResult
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
        // Text, in order of preference: already transcribed (the marker file
        // makes this step idempotent), Pebble's own transcript, or OpenAI on
        // the audio. Only then is the destination consulted.
        val text = item.transcript() ?: item.meta.pebbleTranscript ?: run {
            val audio = item.audio()
                ?: return StepResult.Permanent("no audio and no transcript")
            when (val result = transcriber.transcribe(audio)) {
                is TranscribeResult.Text -> {
                    // Written before use: a crash between transcription and
                    // delivery must not buy the same audio twice.
                    store.writeTranscript(item, result.text)
                    log.record(item.name, "transcribed", "${result.text.length} chars")
                    result.text
                }
                is TranscribeResult.Failed -> return result.result
            }
        }

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

/**
 * Telegram until stage 4. Permanent, not Transient — Transient blocks the
 * queue head, and "not built yet" does not resolve on a timer, so one
 * single-click would dam every note behind it with no way to intervene
 * (Discard only reaches `failed/`). Parked as failed, the items are
 * visible, keepable, and one Retry away from delivery once stage 4 lands.
 */
class StubTelegramSender : PipelineWorker.TelegramSender {
    override fun send(item: RecordingStore.Item, text: String): StepResult =
        StepResult.Permanent("Telegram delivery is not built yet — kept here until it is")
}
