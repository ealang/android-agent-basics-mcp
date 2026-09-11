package dev.ealang.phonemcp.webhook

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
    private val telegramSender: TelegramSender,
    private val onDrainFinished: (DrainReport) -> Unit,
) {

    /** The Telegram destination, behind an interface so tests can fake it. */
    interface TelegramSender {
        /** Hand one item's text to Telegram. Success means *handed off*, not delivered. */
        fun send(item: RecordingStore.Item, text: String): StepResult

        /** What became of an item [send] already handed off. */
        fun reconcile(item: RecordingStore.Item): ReconcileResult
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
            // Handed off already: ask the destination what became of it.
            // Whatever the answer, it does not block the items behind it —
            // Pending here means TDLib's own outbox is still working.
            if (item.processorState(PROCESSOR_TELEGRAM) != null) {
                when (val settled = telegramSender.reconcile(item)) {
                    is ReconcileResult.Delivered -> {
                        completed++
                        log.record(item.name, "completed")
                        store.complete(item)
                    }
                    is ReconcileResult.Failed -> {
                        newlyFailed++
                        log.record(item.name, "failed", settled.why)
                        store.fail(item, settled.why)
                    }
                    is ReconcileResult.Pending -> {}
                }
                continue
            }

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
                    if (item.processorState(PROCESSOR_TELEGRAM) != null) {
                        // Handed off, not done: the item stays as the record
                        // that TDLib owes a confirmation, and stops blocking
                        // the queue (the skip at the top of this loop).
                        // Deletion is the confirmation's job.
                    } else {
                        completed++
                        log.record(item.name, "completed")
                        store.complete(item)
                    }
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

        val destination = WebhookConfig.destinationFor(item.meta.trigger)
        // Where the text came from and where it is going — the log line that
        // answers "why does this note say what it says".
        val source = when {
            item.transcript() != null -> "OpenAI transcript"
            item.meta.pebbleTranscript != null -> "Pebble transcript"
            else -> "?"
        }
        log.record(item.name, "delivering", "$source, ${text.length} chars → ${destination.name.lowercase()}")

        return when (destination) {
            Destination.NOTE -> noteWriter.write(text, recordedAt)
            Destination.TELEGRAM -> telegramSender.send(item, text)
        }
    }

    companion object {
        private const val TAG = "phone-mcp"
        const val PROCESSOR_TELEGRAM = "telegram"
    }
}
