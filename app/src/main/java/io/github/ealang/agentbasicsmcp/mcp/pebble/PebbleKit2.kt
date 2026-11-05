package io.github.ealang.agentbasicsmcp.mcp.pebble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The one PebbleKit 2 request this app makes: start a watchapp, and hear
 * back whether the watch reports it running.
 *
 * PebbleKit 2 is CoreApp's newer companion API — a bound service that
 * answers each request, where the classic broadcasts answer nothing. It is
 * used here for launching alone, because the classic START has a failure
 * mode worse than silence: CoreApp handles STARTs one at a time and waits
 * for the watch to report the app running, and for a UUID the watch does
 * not have that report never comes, so one bad START parks every later one
 * until CoreApp is restarted (as of CoreApp 1.x, November 2026; fix sent
 * upstream). The PebbleKit 2 start gives each request its own ten-second
 * limit, so the same bad UUID costs ten seconds and a clear answer.
 * Starting an app needs no companion declaration from the watchapp; sending
 * to one does, which is why SEND stays classic.
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

    private const val KEY_ACTION = "ACTION"
    private const val KEY_WATCHAPP_UUID = "WATCHAPP_UUID"
    private const val KEY_TRANSMISSION_RESULTS = "TRANSMISSION_RESULTS"
    private const val KEY_RESULT_TYPE = "TYPE"
    private const val KEY_RESULT_MESSAGE = "MESSAGE"
    private const val ACTION_START_APP = "START_APP"

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

    /**
     * Asks CoreApp to start [uuid] on every connected watch and waits for its
     * answer. Returns once the watch reports the app running, or when CoreApp
     * says why not — most often that the watch never reported it, which is what
     * an app the watch does not have looks like.
     */
    fun start(context: Context, uuid: String): Launch {
        val reply = request(
            context,
            Bundle().apply {
                putString(KEY_ACTION, ACTION_START_APP)
                putString(KEY_WATCHAPP_UUID, uuid)
            },
        ) ?: return Launch.Failed("the Pebble app did not answer the launch request.")
        return interpret(uuid, reply)
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

    /**
     * CoreApp answers with one result per watch it tried. Any watch reporting
     * success is enough; otherwise the first failure is reported, as what was
     * observed and nothing more — the result kinds that are not a timeout are
     * for sending, not starting, and all mean the same to the caller. The
     * kind itself goes to logcat.
     */
    private fun interpret(uuid: String, reply: Bundle): Launch {
        val results = reply.getBundle(KEY_TRANSMISSION_RESULTS)
            ?: return Launch.Failed("the Pebble app did not understand the launch request.")
        val perWatch = results.keySet().mapNotNull { results.getBundle(it) }
        if (perWatch.isEmpty()) return Launch.Failed("no watch is connected.")
        if (perWatch.any { it.getString(KEY_RESULT_TYPE) == "SUCCESS" }) {
            Log.i(TAG, "START $uuid: the watch reports it running")
            return Launch.Running
        }
        val first = perWatch.first()
        val type = first.getString(KEY_RESULT_TYPE)
        Log.w(TAG, "START $uuid: $type ${first.getString(KEY_RESULT_MESSAGE) ?: ""}")
        return Launch.Failed(
            when (type) {
                "FAILED_TIMEOUT" -> "the watch did not report the app running within 10s."
                "FAILED_WATCH_NOT_CONNECTED" -> "no watch is connected."
                else -> "the Pebble app did not start the app."
            },
        )
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
