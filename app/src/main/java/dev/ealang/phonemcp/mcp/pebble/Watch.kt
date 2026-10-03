package dev.ealang.phonemcp.mcp.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the Pebble watch through the CoreApp companion.
 *
 * CoreApp still listens for the PebbleKit Classic broadcasts, registered at
 * runtime and exported, so this needs no library and no cooperation from
 * CoreApp. Unlike the clock-app intents these are broadcasts, not activity
 * starts, so none of the background-activity restrictions apply.
 */
object Watch {

    private const val TAG = "PhoneMcpWatch"
    const val COREAPP_PACKAGE = "coredevices.coreapp"

    private const val ACTION_START = "com.getpebble.action.app.START"
    private const val ACTION_STOP = "com.getpebble.action.app.STOP"
    private const val ACTION_SEND = "com.getpebble.action.app.SEND"
    private const val ACTION_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK"
    private const val ACTION_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"
    private const val EXTRA_UUID = "uuid"
    private const val EXTRA_TRANSACTION_ID = "transaction_id"
    private const val EXTRA_MSG_DATA = "msg_data"

    /** Read-only, exported by CoreApp. The one thing here that can be read back. */
    private val STATE_URI: Uri = Uri.parse("content://com.getpebble.android.provider.basalt/state")

    /**
     * A fork of the stock Timer watchapp, which unlike the original will
     * start a timer on request from the phone. Source and UUID:
     * github.com/ealang/coredevices-pebble-timer
     */
    private const val TIMER_UUID = "6ba47813-25c2-4c9e-b93a-f6f24372b4c3"

    /** Names for watchapps installed on the watch, so a UUID need not be quoted. */
    val KNOWN_APPS = mapOf("timer" to TIMER_UUID)

    /** How long to keep trying to get the watchapp up, and how long each go gets. */
    private const val APP_LAUNCH_TIMEOUT_MS = 10_000L
    private const val LAUNCH_ATTEMPT_MS = 1_000L
    private const val ACK_TIMEOUT_MS = 5_000L

    private val UUID_FORMAT = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** CoreApp narrows this to a byte, so it has to stay in range. */
    private val transactions = AtomicInteger(0)

    sealed interface Result {
        /** The broadcast was sent. The watch does not report back. */
        data object Sent : Result
        data class UnknownApp(val given: String) : Result
    }

    sealed interface HandshakeResult {
        /** The watch acknowledged the payload. Delivery, not understanding. */
        data object Acknowledged : HandshakeResult
        /** The payload reached the watch and the watchapp refused it. */
        data object Refused : HandshakeResult
        /** The payload was sent and nothing came back within the wait. */
        data object NoReply : HandshakeResult
        /** No ping was ever acknowledged; the payload was never sent. */
        data object NeverCameUp : HandshakeResult
        data class UnknownApp(val given: String) : HandshakeResult
        /** Never sent: the tuples do not fit CoreApp's wire format. */
        data class Malformed(val why: String) : HandshakeResult
    }

    sealed interface SendResult {
        /** The watch acknowledged delivery. Delivery, not understanding. */
        data object Acknowledged : SendResult
        /** The watch answered NACK — reached, and refused. */
        data object Refused : SendResult
        /** Sent, and nothing came back within the wait. */
        data object NoReply : SendResult
        data class UnknownApp(val given: String) : SendResult
        /** Never sent: the tuples do not fit CoreApp's wire format. */
        data class Malformed(val why: String) : SendResult
    }

    fun launch(context: Context, app: String): Result = send(context, ACTION_START, app)

    fun stop(context: Context, app: String): Result = send(context, ACTION_STOP, app)

    /**
     * The whole handshake in one call: get the watchapp running, prove it is
     * up, send the payload once.
     *
     * CoreApp only forwards a message to a watchapp that is already running and
     * drops it silently otherwise, so the app has to be launched first and the
     * launch is fire-and-forget — nothing reports when it finishes. Rather than
     * guess at a duration, wait for the app to say so itself: see
     * [awaitWatchapp]. The [pingKey] is the caller's claim that the target app
     * deliberately ignores that key — skill-file knowledge; a wrong one means
     * the ping *does* something, up to ten times per call.
     *
     * The payload is then sent exactly once and never repeated here. A repeat
     * could race a slow acknowledgement and apply the payload twice, and by
     * this point there is nothing left to retry for — the app is known to be
     * listening before the first byte of it goes out. Whether a resend is safe
     * after [HandshakeResult.NoReply] depends on what the keys do, which only
     * the caller knows.
     *
     * Whether a watch is connected at all is settled by `Preflight.watch` before
     * this is called, and is not revisited here: if it drops mid-sequence there
     * is nothing to do about it that failing does not already do.
     */
    fun launchAndSend(
        context: Context,
        app: String,
        pingKey: Int,
        tuples: JSONArray,
    ): HandshakeResult {
        val uuid = resolve(app) ?: return HandshakeResult.UnknownApp(app)
        checkWireFormat(tuples)?.let { return HandshakeResult.Malformed(it) }
        val replies = Replies()
        registerForReplies(context, replies)

        return try {
            if (!awaitWatchapp(context, replies, uuid, pingKey)) {
                return HandshakeResult.NeverCameUp
            }

            val transaction = nextTransaction()
            replies.expectMessage(transaction)
            context.sendBroadcast(message(uuid, transaction, tuples.toString()))
            Log.i(TAG, "launch_and_send -> $uuid, transaction $transaction: $tuples")

            when {
                !replies.messageReplied.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) -> {
                    Log.w(TAG, "launch_and_send $uuid: no reply to transaction $transaction")
                    HandshakeResult.NoReply
                }
                replies.messageAcknowledged.get() -> HandshakeResult.Acknowledged
                else -> {
                    Log.w(TAG, "launch_and_send $uuid: the watchapp refused the payload")
                    HandshakeResult.Refused
                }
            }
        } finally {
            context.unregisterReceiver(replies)
        }
    }

    /**
     * The fire-once side of [launchAndSend]: one raw AppMessage to any watchapp,
     * tuples passed through to CoreApp's wire format untouched, the watch's
     * own answer back. Advanced tier — no judgment about what the keys mean,
     * and an acknowledgement of a key the app never reads looks exactly like
     * success. What the keys mean is skill-file knowledge.
     *
     * The message is sent cold: no launch, no ping. A closed watchapp
     * swallows it in silence (no reply, not an error — CoreApp has no
     * receiver for an app that is not running). That is not this function's
     * problem to solve: the "no reply" result already distinguishes reached
     * from not, and a caller who knows an inert key for the target app gets
     * the whole launch-ping-send pattern from [launchAndSend] instead.
     */
    fun sendMessage(context: Context, app: String, tuples: JSONArray): SendResult {
        val uuid = resolve(app) ?: return SendResult.UnknownApp(app)
        checkWireFormat(tuples)?.let { return SendResult.Malformed(it) }
        val replies = Replies()
        registerForReplies(context, replies)

        return try {
            val transaction = nextTransaction()
            replies.expectMessage(transaction)
            context.sendBroadcast(message(uuid, transaction, tuples.toString()))
            Log.i(TAG, "pebblekit_send_message -> $uuid, transaction $transaction: $tuples")

            when {
                !replies.messageReplied.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) ->
                    SendResult.NoReply
                replies.messageAcknowledged.get() -> SendResult.Acknowledged
                else -> SendResult.Refused
            }
        } finally {
            context.unregisterReceiver(replies)
        }
    }

    /**
     * The one validation the generic send performs, and it is wire format, not
     * judgment: CoreApp's tuple parser hard-requires `length` on every tuple
     * (`getInt` throws on absence) and only knows widths 1, 2 and 4 for
     * int/uint — and it parses inside a flow collector with no catch, so one
     * malformed tuple does not just fail, it kills CoreApp's send listener for
     * that watchapp until the app is restarted on the watch. Found live,
     * 2026-09-06. A message that would do that is refused here instead.
     *
     * Returns the reason the tuples are unsendable, or null when they are fine.
     */
    private fun checkWireFormat(tuples: JSONArray): String? {
        for (i in 0 until tuples.length()) {
            val t = tuples.optJSONObject(i)
                ?: return "tuple $i is not an object"
            if (!t.has("key")) return "tuple $i has no key"
            val type = t.optString("type")
            if (type !in setOf("int", "uint", "string", "bytes")) {
                return "tuple $i has type \"$type\"; the wire format knows int, uint, string, bytes"
            }
            if (!t.has("value")) return "tuple $i has no value"
            if (!t.has("length")) return "tuple $i has no length, which the wire format requires"
            if (type in setOf("int", "uint") && t.optInt("length") !in setOf(1, 2, 4)) {
                return "tuple $i has length ${t.optInt("length")}; int and uint must be 1, 2 or 4 bytes"
            }
        }
        return null
    }

    /**
     * CoreApp broadcasts replies to everyone rather than back to the sender,
     * so the receiver has to be listening for an external app.
     */
    private fun registerForReplies(context: Context, replies: Replies) {
        val filter = IntentFilter().apply {
            addAction(ACTION_RECEIVE_ACK)
            addAction(ACTION_RECEIVE_NACK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(replies, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(replies, filter)
        }
    }

    /**
     * Gets a watchapp running, by launching it and asking whether it is
     * there until it answers.
     *
     * An acknowledgement is the only evidence available that the app is up, and
     * it is good evidence: CoreApp registers the `SEND` receiver per *running*
     * watchapp, so with the app closed there is no receiver, the broadcast lands
     * nowhere and nothing at all comes back. What an acknowledgement does not
     * prove is that the message meant anything — which is the point here,
     * because [pingKey] is one the app deliberately ignores, so this can be
     * repeated as often as needed with no effect on the watch.
     *
     * The launch is retried alongside the ping rather than sent once up front:
     * it is fire-and-forget, so a lost one is otherwise indistinguishable from a
     * slow one, and CoreApp documents it as idempotent.
     */
    private fun awaitWatchapp(
        context: Context,
        replies: Replies,
        uuid: String,
        pingKey: Int,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + APP_LAUNCH_TIMEOUT_MS
        while (true) {
            context.sendBroadcast(intentTo(ACTION_START).putExtra(EXTRA_UUID, uuid))

            val transaction = nextTransaction()
            replies.expectPing(transaction)
            context.sendBroadcast(message(uuid, transaction, int32Payload(pingKey, 0)))

            if (replies.watchappUp.await(LAUNCH_ATTEMPT_MS, TimeUnit.MILLISECONDS)) return true
            if (SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "Watchapp $uuid did not come up within $APP_LAUNCH_TIMEOUT_MS ms")
                return false
            }
        }
    }

    /**
     * CoreApp's replies for one [launchAndSend] or [sendMessage] call, matched back
     * to what was sent by the transaction id it echoes.
     *
     * Pings are pooled rather than tracked one by one: a late acknowledgement of
     * an earlier ping proves exactly what a prompt one does, so a round trip
     * slower than [LAUNCH_ATTEMPT_MS] costs a few spare pings instead of the
     * whole wait.
     */
    private class Replies : BroadcastReceiver() {

        private val pings = Collections.synchronizedSet(mutableSetOf<Int>())
        val watchappUp = CountDownLatch(1)

        @Volatile private var messageTransaction = -1
        val messageReplied = CountDownLatch(1)
        val messageAcknowledged = AtomicBoolean(false)

        fun expectPing(transaction: Int) = pings.add(transaction)

        fun expectMessage(transaction: Int) {
            messageTransaction = transaction
        }

        override fun onReceive(context: Context, intent: Intent) {
            val transaction = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1)
            if (transaction < 0) return
            val acknowledged = intent.action == ACTION_RECEIVE_ACK
            when {
                transaction == messageTransaction -> {
                    messageAcknowledged.set(acknowledged)
                    messageReplied.countDown()
                }
                acknowledged && pings.contains(transaction) -> watchappUp.countDown()
            }
        }
    }

    private fun nextTransaction(): Int = transactions.getAndIncrement() and 0xff

    private fun message(uuid: String, transaction: Int, payload: String): Intent =
        intentTo(ACTION_SEND)
            .putExtra(EXTRA_UUID, uuid)
            .putExtra(EXTRA_TRANSACTION_ID, transaction)
            .putExtra(EXTRA_MSG_DATA, payload)

    /** The wire form PebbleKit Classic expects: one tuple, as a 4-byte signed int. */
    private fun int32Payload(key: Int, value: Int): String = JSONArray().put(
        JSONObject()
            .put("key", key)
            .put("type", "int")
            .put("length", 4)
            .put("value", value),
    ).toString()

    private fun intentTo(action: String): Intent = Intent(action).setPackage(COREAPP_PACKAGE)

    private fun send(context: Context, action: String, app: String): Result {
        val uuid = resolve(app) ?: return Result.UnknownApp(app)
        // Addressed to CoreApp rather than broadcast at large: nothing else
        // should see it, and an implicit broadcast is easier to lose.
        context.sendBroadcast(intentTo(action).putExtra(EXTRA_UUID, uuid))
        Log.i(TAG, "$action -> $uuid")
        return Result.Sent
    }

    private fun resolve(app: String): String? {
        val trimmed = app.trim()
        if (UUID_FORMAT.matches(trimmed)) return trimmed.lowercase()
        return KNOWN_APPS[trimmed.lowercase()]
    }

    /**
     * Column 0 of the single row is a connected flag. Absent provider or empty
     * cursor means CoreApp has not finished starting, which is also "no watch".
     */
    fun connected(context: Context): Boolean = try {
        context.contentResolver.query(STATE_URI, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the CoreApp connection state", e)
        false
    }
}
