package dev.ealang.phonemcp

import android.content.Context
import dev.ealang.phonemcp.telegram.TelegramEngine
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real Telegram destination (docs/webhook-pipeline.md §7). Handoff and
 * confirmation are separate: [send] records the accepted message id in
 * `telegram.json` and returns — the item stops blocking the queue but
 * *stays* as the visible record that confirmation is owed — and [confirm],
 * fed by TDLib's UpdateMessageSendSucceeded through the service, is what
 * finally deletes it. Never re-send on a gap: TDLib's own persistent outbox
 * is retrying underneath, and a timeout that re-sends is how duplicates are
 * made. The stuck cases (send failed, session dead) are step 3's reconcile.
 */
class TelegramDelivery(
    context: Context,
    private val store: RecordingStore,
    private val log: PipelineLog,
) : PipelineWorker.TelegramSender {

    private val config = Config(context)

    /** Worker thread. Blocks on TDLib's synchronous answer — local and quick. */
    override fun send(item: RecordingStore.Item, text: String): StepResult {
        // Read at pickup, not at receipt: changing the chat id redirects
        // queued items, the same rescue path every destination has (§3).
        val chatId = config.telegramChatId
            ?: return StepResult.Transient("no Telegram chat id is set — save one in the app")

        val message = MESSAGE_TEMPLATE.replace("{text}", text)
        var messageId: Long? = null
        var problem: String? = null
        val latch = CountDownLatch(1)
        TelegramEngine.sendText(chatId, message) { id, why ->
            messageId = id
            problem = why
            latch.countDown()
        }
        if (!latch.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
            // TDLib answers a SendMessage locally even when offline, so a
            // silent client is broken, not slow. Nothing was recorded, so a
            // later retry cannot duplicate — the send never got an id.
            return StepResult.Transient("Telegram did not answer")
        }
        val id = messageId
            ?: return StepResult.Transient(problem ?: "Telegram send failed")

        store.writeProcessorState(
            item,
            PipelineWorker.PROCESSOR_TELEGRAM,
            JSONObject()
                .put("chatId", chatId)
                .put("messageId", id)
                .put("handedOffAtMs", System.currentTimeMillis()),
        )
        log.record(item.name, "handed off", "message $id → chat $chatId")
        return StepResult.Success
    }

    /**
     * TDLib confirmed the message reached the server: delete the item it
     * belongs to. Runs on the worker thread — the single writer — via
     * [PipelineWorker.post]; the id is the temporary one recorded at handoff,
     * which is exactly what UpdateMessageSendSucceeded carries as oldMessageId.
     */
    fun confirm(oldMessageId: Long) {
        for (item in store.pending()) {
            val state = item.processorState(PipelineWorker.PROCESSOR_TELEGRAM) ?: continue
            if (state.optLong("messageId") == oldMessageId) {
                log.record(item.name, "delivered")
                store.complete(item)
                return
            }
        }
        // No match: an item already reconciled away, or a send from another
        // surface on this account. Normal, not an error.
    }

    companion object {
        /** The Python gateway's default; provenance for the agent reading it. */
        private const val MESSAGE_TEMPLATE = "[voice via Pebble] {text}"

        /** Generous — the answer is a local round-trip into TDLib, not the network. */
        private const val ANSWER_TIMEOUT_S = 20L
    }
}
