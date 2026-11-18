package io.github.ealang.agentbasicsmcp.mcp.pebble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import android.os.RemoteException
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The requests this app makes of PebbleKit 2, CoreApp's newer companion
 * API: start a watchapp, stop one, send one an AppMessage, and read which
 * watches are connected and what each is running.
 *
 * PebbleKit 2 is a bound service that answers each request, where the
 * classic broadcasts answer nothing. Starting shows the difference most:
 * CoreApp handles classic STARTs one at a time and waits for the watch to
 * report the app running, and for a UUID the watch does not have that
 * report never comes, so one bad START parks every later one until CoreApp
 * is restarted (as of CoreApp 1.x, November 2026). The PebbleKit 2 start
 * gives each request its own ten-second limit, so the same bad UUID costs
 * ten seconds and a clear answer.
 *
 * Starting, stopping and reading state work for any watchapp. Sending does
 * not: CoreApp only accepts a send from a package the watchapp names as its
 * Android companion, so a watchapp that names none is still reached over
 * the classic broadcasts in [Watch]. The inbound half, what a watchapp
 * sends to its companion, arrives at [PebbleKit2ListenerService].
 *
 * The binder contract is spoken directly rather than through the published
 * client library, which as of 1.3.x is built against Kotlin 2.4 and
 * compileSdk 37 — a toolchain change this app has no other reason to make.
 * The contract is two AIDL interfaces under src/main/aidl and the Bundle
 * keys below, from PebbleKitAndroid2 (Apache 2.0).
 */
object PebbleKit2 {

    private const val TAG = "AgentBasicsMcpPebbleKit2"

    /** The intent action CoreApp's PebbleKit 2 service is exported under. */
    private const val SERVICE_ACTION = "io.rebble.pebblekit2.SEND_DATA_TO_WATCH"

    internal const val KEY_ACTION = "ACTION"
    internal const val KEY_WATCHAPP_UUID = "WATCHAPP_UUID"
    internal const val KEY_DATA_DICTIONARY = "DATA_DICTIONARY"
    /** Also the key a listener's ACK or NACK goes back under. */
    internal const val KEY_TRANSMISSION_RESULTS = "TRANSMISSION_RESULTS"
    internal const val KEY_RESULT_TYPE = "TYPE"
    private const val KEY_RESULT_MESSAGE = "MESSAGE"
    private const val ACTION_START_APP = "START_APP"
    private const val ACTION_STOP_APP = "STOP_APP"
    private const val ACTION_SEND_DATA = "SEND_DATA_TO_WATCH"

    /** CoreApp's PebbleKit 2 state provider: read-only, exported. */
    private val WATCHES_URI: Uri =
        Uri.parse("content://${Watch.COREAPP_PACKAGE}.pebblekit/connectedWatches")
    private val ACTIVE_APP_URI: Uri =
        Uri.parse("content://${Watch.COREAPP_PACKAGE}.pebblekit/activeApp")

    /**
     * How long [launchAndSend] keeps offering the message to an app the watch
     * has reported running but CoreApp has not yet opened a session for.
     */
    private const val SESSION_TIMEOUT_MS = 10_000L
    private const val SESSION_RETRY_MS = 300L

    /** Binding is local and quick; this only guards against a hung CoreApp. */
    private const val BIND_TIMEOUT_MS = 5_000L

    /** CoreApp gives each request ten seconds against the watch; a little over that. */
    private const val REQUEST_TIMEOUT_MS = 15_000L

    sealed interface Launch {
        /** The watch reported the app running. */
        data object Running : Launch
        /** Not running. [why] is one sentence written for the calling model to repeat. */
        data class Failed(val why: String) : Launch
    }

    /** What CoreApp answered to a send. */
    sealed interface Send {
        /** The watch acknowledged delivery. Delivery, not understanding. */
        data object Acknowledged : Send
        /** Not delivered. [why] is one sentence written for the calling model to repeat. */
        data class Failed(val why: String) : Send
        /**
         * Not delivered because CoreApp has no session for the watchapp:
         * closed, not installed, another app in front — or open, and not one
         * that names this app as its companion, which CoreApp answers the
         * same way. Kept apart from [Failed] because it is the one failure
         * worth retrying straight after a launch.
         */
        data object NotOpen : Send
    }

    sealed interface Stop {
        /** CoreApp passed the stop to the watch. The watch does not confirm it. */
        data object Accepted : Stop
        data class Failed(val why: String) : Stop
    }

    /**
     * Asks CoreApp to start [uuid] on every connected watch and waits for its
     * answer. Returns once the watch reports the app running, or when CoreApp
     * says why not — most often that the watch never reported it, which is what
     * an app the watch does not have looks like.
     */
    fun start(context: Context, uuid: String): Launch {
        val reply = request(context, action(ACTION_START_APP, uuid))
            ?: return Launch.Failed("the Pebble app did not answer the launch request.")
        return when (val answer = interpret("START", uuid, reply)) {
            Answer.Success -> Launch.Running
            Answer.Malformed -> Launch.Failed("the Pebble app did not understand the launch request.")
            Answer.NoWatch -> Launch.Failed("no watch is connected.")
            is Answer.Failure -> Launch.Failed(
                when (answer.type) {
                    "FAILED_TIMEOUT" -> "the watch did not report the app running within 10s."
                    else -> "the Pebble app did not start the app."
                },
            )
        }
    }

    fun stop(context: Context, uuid: String): Stop {
        val reply = request(context, action(ACTION_STOP_APP, uuid))
            ?: return Stop.Failed("the Pebble app did not answer the stop request.")
        return when (interpret("STOP", uuid, reply)) {
            Answer.Success -> Stop.Accepted
            Answer.Malformed -> Stop.Failed("the Pebble app did not understand the stop request.")
            Answer.NoWatch -> Stop.Failed("no watch is connected.")
            is Answer.Failure -> Stop.Failed("the Pebble app did not stop the app.")
        }
    }

    /**
     * One AppMessage to [uuid], sent once. [tuples] is the normalised form
     * [Watch.toWire] produces, so it is known to fit the wire format.
     *
     * Unlike the classic send this always comes back with an answer, and a
     * send to an app that is not running is one of them rather than silence.
     */
    fun send(context: Context, uuid: String, tuples: JSONArray): Send {
        val data = action(ACTION_SEND_DATA, uuid).apply {
            putBundle(KEY_DATA_DICTIONARY, toDictionary(tuples))
        }
        val reply = request(context, data)
            ?: return Send.Failed("the Pebble app did not answer the send request.")
        return when (val answer = interpret("SEND", uuid, reply)) {
            Answer.Success -> Send.Acknowledged
            Answer.Malformed -> Send.Failed("the Pebble app did not understand the send request.")
            Answer.NoWatch -> Send.Failed("no watch is connected.")
            is Answer.Failure -> when (answer.type) {
                "FAILED_DIFFERENT_APP_OPEN" -> Send.NotOpen
                "FAILED_WATCH_NACKED" ->
                    Send.Failed("NACK — the watch received the message and refused it.")
                "FAILED_NO_PERMISSIONS" -> Send.Failed(
                    "the Pebble app refused the send. The watchapp must name this app's " +
                        "package (${context.packageName}) as its Android companion, and the " +
                        "user must allow it in the Pebble app if asked.",
                )
                "FAILED_TIMEOUT" -> Send.Failed(
                    "no answer from the watch within 10s. The message may or may not have arrived.",
                )
                else -> Send.Failed("the Pebble app did not send the message.")
            }
        }
    }

    sealed interface LaunchAndSend {
        data object Acknowledged : LaunchAndSend
        /** The watch never reported the app running; nothing was sent. */
        data class NotLaunched(val why: String) : LaunchAndSend
        /** The app came up and the send failed; [why] says how. */
        data class NotSent(val why: String) : LaunchAndSend
    }

    /**
     * Start the watchapp, then deliver one message.
     *
     * The start returns when the watch reports the app running, and CoreApp
     * opens its session for the app a beat after that. A send in the gap is
     * answered "not open" — refused before anything goes to the watch, so
     * offering it again cannot deliver it twice. That is what makes a retry
     * safe here and why no ping is needed, where the classic route has only
     * silence to go on.
     */
    fun launchAndSend(context: Context, uuid: String, tuples: JSONArray): LaunchAndSend {
        when (val launched = start(context, uuid)) {
            Launch.Running -> Unit
            is Launch.Failed -> return LaunchAndSend.NotLaunched(launched.why)
        }
        val deadline = SystemClock.elapsedRealtime() + SESSION_TIMEOUT_MS
        while (true) {
            when (val sent = send(context, uuid, tuples)) {
                Send.Acknowledged -> return LaunchAndSend.Acknowledged
                is Send.Failed -> return LaunchAndSend.NotSent(sent.why)
                Send.NotOpen -> {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        return LaunchAndSend.NotSent(
                            "it was not open to messages from this app within 10s. The " +
                                "usual reason is that the watchapp does not name this app as " +
                                "its Android companion. The message was not delivered.",
                        )
                    }
                    Thread.sleep(SESSION_RETRY_MS)
                }
            }
        }
    }

    /**
     * The connected watches and what each is running, as CoreApp's state
     * provider reports them. Null when the provider cannot be read at all.
     *
     * The watch's serial number is how the provider keys the second query
     * and goes no further than that: it identifies the user's hardware and
     * tells a caller nothing it can act on.
     */
    fun state(context: Context): JSONObject? {
        try {
            val watches = JSONArray()
            val serials = mutableListOf<String>()
            val cursor = context.contentResolver.query(WATCHES_URI, null, null, null, null)
                ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    serials += c.getString(c.getColumnIndexOrThrow("ID"))
                    val firmware = listOf("MAJOR", "MINOR", "PATCH")
                        .map { c.getInt(c.getColumnIndexOrThrow("FIRMWARE_VERSION_$it")) }
                        .joinToString(".")
                    val tag = c.getString(c.getColumnIndexOrThrow("FIRMWARE_VERSION_TAG")).orEmpty()
                    watches.put(
                        JSONObject()
                            .put("name", c.getString(c.getColumnIndexOrThrow("NAME")))
                            .put("platform", c.getString(c.getColumnIndexOrThrow("PLATFORM")))
                            .put("firmware", if (tag.isEmpty()) firmware else "$firmware-$tag"),
                    )
                }
            }
            serials.forEachIndexed { i, serial ->
                watches.getJSONObject(i).put("running", activeApp(context, serial) ?: JSONObject.NULL)
            }
            return JSONObject().put("watches", watches)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the PebbleKit 2 state provider", e)
            return null
        }
    }

    private fun activeApp(context: Context, serial: String): JSONObject? {
        val cursor = context.contentResolver
            .query(Uri.withAppendedPath(ACTIVE_APP_URI, serial), null, null, null, null)
            ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject()
                .put("app", c.getString(c.getColumnIndexOrThrow("ID")))
                .put("name", c.getString(c.getColumnIndexOrThrow("NAME")) ?: JSONObject.NULL)
                .put(
                    "type",
                    when (c.getInt(c.getColumnIndexOrThrow("TYPE"))) {
                        0 -> "watchface"
                        1 -> "watchapp"
                        else -> "unknown"
                    },
                )
        }
    }

    private fun action(action: String, uuid: String) = Bundle().apply {
        putString(KEY_ACTION, action)
        putString(KEY_WATCHAPP_UUID, uuid)
    }

    /**
     * The caller's tuples as a PebbleKit 2 dictionary: per key, a type name
     * and a value, integers always carried as a long whatever their width.
     * Keys are unsigned on the wire and written that way.
     */
    private fun toDictionary(tuples: JSONArray): Bundle = Bundle().apply {
        for (i in 0 until tuples.length()) {
            val t = tuples.getJSONObject(i)
            val key = (t.getLong("key") and 0xffffffffL).toString()
            when (val type = t.getString("type")) {
                "string" -> {
                    putString("${key}_TYPE", "String")
                    putString("${key}_VALUE", t.getString("value"))
                }
                "bytes" -> {
                    putString("${key}_TYPE", "ByteArray")
                    putByteArray("${key}_VALUE", Base64.decode(t.getString("value"), Base64.NO_WRAP))
                }
                else -> {
                    val bits = t.getInt("length") * 8
                    putString("${key}_TYPE", if (type == "uint") "UInt$bits" else "Int$bits")
                    putLong("${key}_VALUE", t.getLong("value"))
                }
            }
        }
    }

    /**
     * A dictionary CoreApp delivered, as the same tuples a send takes, in key
     * order: integers keep their width, so a received message can be handed
     * back to a send unchanged. An entry of a type this does not know is
     * left out and logged, as the client library does.
     */
    internal fun toTuples(dictionary: Bundle): JSONArray {
        val keys = dictionary.keySet()
            .filter { it.endsWith("_TYPE") }
            .mapNotNull { it.removeSuffix("_TYPE").toLongOrNull() }
            .sorted()
        val out = JSONArray()
        for (key in keys) {
            val type = dictionary.getString("${key}_TYPE")
            val valueKey = "${key}_VALUE"
            val tuple = JSONObject().put("key", key)
            when (type) {
                "String" -> tuple.put("type", "string").put("value", dictionary.getString(valueKey).orEmpty())
                "ByteArray" -> tuple.put("type", "bytes").put(
                    "value",
                    Base64.encodeToString(dictionary.getByteArray(valueKey) ?: ByteArray(0), Base64.NO_WRAP),
                )
                "Int8", "Int16", "Int32", "UInt8", "UInt16", "UInt32" -> tuple
                    .put("type", if (type.startsWith("U")) "uint" else "int")
                    .put("length", type.substringAfter("Int").toInt() / 8)
                    .put("value", dictionary.getLong(valueKey))
                else -> {
                    Log.w(TAG, "Inbound tuple $key has unknown type $type; left out")
                    continue
                }
            }
            out.put(tuple)
        }
        return out
    }

    /**
     * One request over one binding: bind, ask, wait for the callback, unbind.
     * Null when CoreApp never answered — no service to bind, the bind did not
     * complete, or the request went unanswered.
     *
     * Blocks the calling thread, which must not be the main thread: the
     * binding callbacks arrive there.
     */
    private fun request(context: Context, data: Bundle): Bundle? {
        val connection = Connection()
        val intent = Intent(SERVICE_ACTION).setPackage(Watch.COREAPP_PACKAGE)
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                Log.w(TAG, "CoreApp has no PebbleKit 2 service to bind")
                return null
            }
            val service = connection.await(BIND_TIMEOUT_MS)
            if (service == null) {
                Log.w(TAG, "CoreApp's PebbleKit 2 service did not bind within $BIND_TIMEOUT_MS ms")
                return null
            }
            val reply = Reply()
            service.request(data, reply)
            return reply.await(REQUEST_TIMEOUT_MS).also {
                if (it == null) Log.w(TAG, "No PebbleKit 2 answer within $REQUEST_TIMEOUT_MS ms")
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "PebbleKit 2 request failed", e)
            return null
        } finally {
            // Documented as required even when bindService returned false.
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "PebbleKit 2 connection was already gone", e)
            }
        }
    }

    private sealed interface Answer {
        data object Success : Answer
        /** No results bundle at all: CoreApp did not recognise the request. */
        data object Malformed : Answer
        data object NoWatch : Answer
        /** [type] is CoreApp's result kind for the first watch that failed. */
        data class Failure(val type: String?) : Answer
    }

    /**
     * CoreApp answers with one result per watch it tried. Any watch reporting
     * success is enough; otherwise the first failure is reported. The kind
     * and CoreApp's own message go to logcat.
     */
    private fun interpret(what: String, uuid: String, reply: Bundle): Answer {
        val results = reply.getBundle(KEY_TRANSMISSION_RESULTS) ?: return Answer.Malformed
        val perWatch = results.keySet().mapNotNull { results.getBundle(it) }
        if (perWatch.isEmpty()) return Answer.NoWatch
        if (perWatch.any { it.getString(KEY_RESULT_TYPE) == "SUCCESS" }) {
            Log.i(TAG, "$what $uuid: success")
            return Answer.Success
        }
        val first = perWatch.first()
        val type = first.getString(KEY_RESULT_TYPE)
        Log.w(TAG, "$what $uuid: $type ${first.getString(KEY_RESULT_MESSAGE) ?: ""}")
        return if (type == "FAILED_WATCH_NOT_CONNECTED") Answer.NoWatch else Answer.Failure(type)
    }

    private class Connection : ServiceConnection {
        private val settled = CountDownLatch(1)
        private val service = AtomicReference<UniversalRequestResponse?>()

        fun await(timeoutMs: Long): UniversalRequestResponse? {
            settled.await(timeoutMs, TimeUnit.MILLISECONDS)
            return service.get()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.set(binder?.let(UniversalRequestResponse.Stub::asInterface))
            settled.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        override fun onNullBinding(name: ComponentName?) = settled.countDown()

        override fun onBindingDied(name: ComponentName?) = settled.countDown()
    }

    private class Reply : SendDataCallback.Stub() {
        private val answered = CountDownLatch(1)
        private val bundle = AtomicReference<Bundle?>()

        fun await(timeoutMs: Long): Bundle? {
            answered.await(timeoutMs, TimeUnit.MILLISECONDS)
            return bundle.get()
        }

        override fun onResult(result: Bundle?) {
            bundle.set(result)
            answered.countDown()
        }
    }
}
