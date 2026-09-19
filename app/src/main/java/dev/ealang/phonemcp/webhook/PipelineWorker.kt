package dev.ealang.phonemcp.webhook

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.ealang.phonemcp.ServerLog

/**
 * The one thread that mutates the queue past enqueue. Inbound surface:
 * [wake], from any thread — every reason to work (an enqueue, a Retry, a
 * network change, a TDLib event) raises the same flag. Outbound: one
 * callback per drain, with a report; the worker owns no retry timer and
 * does not know who listens. See `docs/webhook-pipeline.md` §4, §10.
 */
class PipelineWorker(
    private val store: RecordingStore,
    private val log: ServerLog,
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
            // process() is idempotent — the marker files make every done
            // step a cheap skip — so it runs on every pass, even for items
            // already handed off to Telegram.
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
                        // Handed off: the item stays as the record that TDLib
                        // owes a confirmation, and does not block the items
                        // behind it — Pending means the outbox is still
                        // working. Deletion is the confirmation's job.
                        when (val settled = telegramSender.reconcile(item)) {
                            is ReconcileResult.Delivered -> {
                                completed++
                                log.bump("sent")
                                log.info("completed", item = item.name)
                                store.complete(item)
                            }
                            is ReconcileResult.Failed -> {
                                newlyFailed++
                                log.error("failed", settled.why, item.name)
                                store.fail(item, settled.why)
                            }
                            is ReconcileResult.Pending -> {}
                        }
                    } else {
                        completed++
                        log.info("completed", item = item.name)
                        store.complete(item)
                    }
                }
                is StepResult.Dropped -> {
                    // Deleted like a completion, but not counted as one:
                    // nothing was sent, so no counter and no gate reset.
                    log.info("dropped empty recording", item = item.name)
                    store.complete(item)
                }
                is StepResult.Permanent -> {
                    newlyFailed++
                    log.error("failed", result.why, item.name)
                    store.fail(item, result.why)
                }
                is StepResult.Transient -> {
                    // Ordering: nothing behind this item may pass it. Stop —
                    // don't sleep — and let the orchestrator decide when to
                    // wake us again. Debug: this line repeats every drain
                    // while blocked, and only the first would be news.
                    log.debug("waiting", result.why, item.name)
                    blockedOn = "${item.name}: ${result.why}"
                    break
                }
            }
        }

        return DrainReport(completed, blockedOn, newlyFailed)
    }

    /**
     * Advance one item through its remaining steps, idempotently. Every
     * press goes to Telegram — routing is fixed, not configured. The
     * hand-off marker, not the delivery, is the record the send ran.
     */
    private fun process(item: RecordingStore.Item): StepResult {
        // The only text is what the watch transcribed; on-phone transcription
        // was cut with the OpenAI dependency (2026-09-18). Note the blank
        // case never reaches here from parsing — Meta coerces a blank
        // transcript to null — so null covers both absent and empty.
        val text = item.meta.pebbleTranscript
        if (text == null) {
            return if (item.audio() != null) {
                // Speech was recorded but never transcribed. Terminal: the
                // item parks in failed/ with its audio intact.
                StepResult.Permanent("no transcript attached")
            } else {
                // Nothing at all — a pocket press or silence.
                StepResult.Dropped
            }
        }

        if (item.processorState(PROCESSOR_TELEGRAM) == null) {
            log.debug("delivering", "${text.length} chars → telegram", item.name)
            return telegramSender.send(item, text)
        }

        return StepResult.Success
    }

    companion object {
        private const val TAG = "phone-mcp"
        const val PROCESSOR_TELEGRAM = "telegram"
    }
}
