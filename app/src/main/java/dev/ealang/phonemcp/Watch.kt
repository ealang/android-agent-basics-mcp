package dev.ealang.phonemcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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

    /** What the watchapp's own setting screen can express, so what it will accept. */
    const val TIMER_MIN_SECONDS = 5L
    const val TIMER_MAX_SECONDS = 86_399L

    /** Long enough for CoreApp to get the watchapp running over BLE. */
    private const val APP_LAUNCH_MS = 2_500L
    private const val ACK_TIMEOUT_MS = 5_000L

    private val UUID_FORMAT = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /** CoreApp narrows this to a byte, so it has to stay in range. */
    private val transactions = AtomicInteger(0)

    sealed interface Result {
        /** The broadcast was sent. The watch does not report back. */
        data object Sent : Result
        data object NoCompanionApp : Result
        data object NoWatchConnected : Result
        data class UnknownApp(val given: String) : Result
    }

    sealed interface TimerResult {
        /** The watchapp acknowledged the message, so the timer is running. */
        data object Confirmed : TimerResult
        data object NoCompanionApp : TimerResult
        data object NoWatchConnected : TimerResult
        /** Launched and sent to, but nothing came back. */
        data object NoAcknowledgement : TimerResult
        /** The watchapp was reached and refused the message. */
        data object Rejected : TimerResult
    }

    fun launch(context: Context, app: String): Result = send(context, ACTION_START, app)

    fun stop(context: Context, app: String): Result = send(context, ACTION_STOP, app)

    /**
     * Starts a countdown of [seconds] in the timer watchapp, bringing it to the
     * front of the watch on the way.
     *
     * CoreApp only forwards a message to a watchapp that is already running,
     * and drops it silently otherwise, so the app has to be launched and given
     * time to come up first. There is deliberately no retry: the failure being
     * guarded against is "not up yet", which waiting fixes and re-sending does
     * not, and a second send that raced a slow acknowledgement would leave the
     * user with two timers.
     */
    fun setTimer(context: Context, seconds: Long): TimerResult {
        if (!companionInstalled(context)) return TimerResult.NoCompanionApp
        if (!watchConnected(context)) return TimerResult.NoWatchConnected

        val transaction = transactions.getAndIncrement() and 0xff
        val acknowledged = AtomicBoolean(false)
        val replied = CountDownLatch(1)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_TRANSACTION_ID, -1) != transaction) return
                acknowledged.set(intent.action == ACTION_RECEIVE_ACK)
                replied.countDown()
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_RECEIVE_ACK)
            addAction(ACTION_RECEIVE_NACK)
        }
        // CoreApp broadcasts the reply to everyone rather than back to us, so
        // this has to be listening for an external sender.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        return try {
            context.sendBroadcast(intentTo(ACTION_START).putExtra(EXTRA_UUID, TIMER_UUID))
            Thread.sleep(APP_LAUNCH_MS)

            context.sendBroadcast(
                intentTo(ACTION_SEND)
                    .putExtra(EXTRA_UUID, TIMER_UUID)
                    .putExtra(EXTRA_TRANSACTION_ID, transaction)
                    .putExtra(EXTRA_MSG_DATA, int32Payload(KEY_START_TIMER, seconds.toInt())),
            )

            when {
                !replied.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) -> {
                    Log.w(TAG, "set_watch_timer $seconds s: no reply to transaction $transaction")
                    TimerResult.NoAcknowledgement
                }
                acknowledged.get() -> {
                    Log.i(TAG, "set_watch_timer $seconds s: acknowledged")
                    TimerResult.Confirmed
                }
                else -> {
                    Log.w(TAG, "set_watch_timer $seconds s: the watchapp refused it")
                    TimerResult.Rejected
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

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
        if (!companionInstalled(context)) return Result.NoCompanionApp
        if (!watchConnected(context)) return Result.NoWatchConnected

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

    private fun companionInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(COREAPP_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Column 0 of the single row is a connected flag. Absent provider or empty
     * cursor means CoreApp has not finished starting, which is also "no watch".
     */
    private fun watchConnected(context: Context): Boolean = try {
        context.contentResolver.query(STATE_URI, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the CoreApp connection state", e)
        false
    }
}
