package dev.ealang.phonemcp

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

    /** AppMessage key the timer watchapp reads a duration from; matches its package.json. */
    private const val KEY_START_TIMER = 20

    /**
     * A key the timer watchapp does not read. Its inbox handler looks for
     * KEY_START_TIMER and discards the whole message when that is absent, so
     * one carrying only this key cannot do anything — which is what makes it
     * safe to send over and over. Must stay unassigned in the watchapp.
     */
    private const val KEY_PING = 1

    /** What the watchapp's own setting screen can express, so what it will accept. */
    const val TIMER_MIN_SECONDS = 5L
    const val TIMER_MAX_SECONDS = 86_399L

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

    sealed interface TimerResult {
        /** The watchapp acknowledged the message, so the timer is running. */
        data object Confirmed : TimerResult
        /** Launched and sent to, but nothing came back. */
        data object NoAcknowledgement : TimerResult
        /** The watchapp was reached and refused the message. */
        data object Rejected : TimerResult
    }

    sealed interface SendResult {
        /** The watch acknowledged delivery. Delivery, not understanding. */
        data object Acknowledged : SendResult
        /** The watch answered NACK — reached, and refused. */
        data object Refused : SendResult
        /** Sent, and nothing came back within the wait. */
        data object NoReply : SendResult
        /** The ping loop ran its course and the app never answered. */
        data object NeverCameUp : SendResult
        data class UnknownApp(val given: String) : SendResult
    }

    fun launch(context: Context, app: String): Result = send(context, ACTION_START, app)

    fun stop(context: Context, app: String): Result = send(context, ACTION_STOP, app)

    /**
     * Starts a countdown of [seconds] in the timer watchapp, bringing it to the
     * front of the watch on the way.
     *
     * CoreApp only forwards a message to a watchapp that is already running and
     * drops it silently otherwise, so the app has to be launched first and the
     * launch is fire-and-forget — nothing reports when it finishes. Rather than
     * guess at a duration, wait for the app to say so itself: see [awaitWatchapp].
     *
     * The duration is then sent exactly once and never repeated. A repeat could
     * race a slow acknowledgement and leave the user with two timers, and by
     * this point there is nothing left to retry for — the app is known to be
     * listening before the first byte of it goes out.
     *
     * Whether a watch is connected at all is settled by `Preflight.watch` before
     * this is called, and is not revisited here: if it drops mid-sequence there
     * is nothing to do about it that failing does not already do.
     */
    fun setTimer(context: Context, seconds: Long): TimerResult {
        val replies = Replies()
        registerForReplies(context, replies)

        return try {
            if (!awaitWatchapp(context, replies, TIMER_UUID, KEY_PING)) {
                return TimerResult.NoAcknowledgement
            }

            val transaction = nextTransaction()
            replies.expectMessage(transaction)
            context.sendBroadcast(
                message(TIMER_UUID, transaction, int32Payload(KEY_START_TIMER, seconds.toInt())),
            )

            when {
                !replies.messageReplied.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) -> {
                    Log.w(TAG, "set_watch_timer $seconds s: no reply to transaction $transaction")
                    TimerResult.NoAcknowledgement
                }
                replies.messageAcknowledged.get() -> {
                    Log.i(TAG, "set_watch_timer $seconds s: acknowledged")
                    TimerResult.Confirmed
                }
                else -> {
                    Log.w(TAG, "set_watch_timer $seconds s: the watchapp refused it")
                    TimerResult.Rejected
                }
            }
        } finally {
            context.unregisterReceiver(replies)
        }
    }

    /**
     * The generic side of [setTimer]: one raw AppMessage to any watchapp,
     * tuples passed through to CoreApp's wire format untouched, the watch's
     * own answer back. Advanced tier — no judgment about what the keys mean,
     * and an acknowledgement of a key the app never reads looks exactly like
     * success. What the keys mean is skill-file knowledge.
     *
     * [pingKey] is the one opt-in mechanism: with it, the launch-and-ping
     * loop from [awaitWatchapp] runs first, exactly as for the timer — but
     * only the caller can know which key an arbitrary watchapp is guaranteed
     * to ignore, so it is never assumed. Without it, the message is sent
     * cold, and a closed watchapp swallows it in silence (no reply, not an
     * error — CoreApp has no receiver for an app that is not running).
     */
    fun sendMessage(context: Context, app: String, tuples: JSONArray, pingKey: Int?): SendResult {
        val uuid = resolve(app) ?: return SendResult.UnknownApp(app)
        val replies = Replies()
        registerForReplies(context, replies)

        return try {
            if (pingKey != null && !awaitWatchapp(context, replies, uuid, pingKey)) {
                return SendResult.NeverCameUp
            }

            val transaction = nextTransaction()
            replies.expectMessage(transaction)
            context.sendBroadcast(message(uuid, transaction, tuples.toString()))
            Log.i(TAG, "send_watch_message -> $uuid, transaction $transaction: $tuples")

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
     * Gets the timer watchapp running, by launching it and asking whether it is
     * there until it answers.
     *
     * An acknowledgement is the only evidence available that the app is up, and
     * it is good evidence: CoreApp registers the `SEND` receiver per *running*
     * watchapp, so with the app closed there is no receiver, the broadcast lands
     * nowhere and nothing at all comes back. What an acknowledgement does not
     * prove is that the message meant anything — which is the point here,
     * because [KEY_PING] means nothing at all, so this can be repeated as often
     * as needed with no effect on the watch.
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
                Log.w(TAG, "The timer app did not come up within $APP_LAUNCH_TIMEOUT_MS ms")
                return false
            }
        }
    }

    /**
     * CoreApp's replies for one [setTimer] or [sendMessage] call, matched back
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
