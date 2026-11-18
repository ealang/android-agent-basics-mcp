package io.github.ealang.agentbasicsmcp.mcp.pebble

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import io.github.ealang.agentbasicsmcp.Config
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse

/**
 * The inbound half of PebbleKit 2: where CoreApp delivers what a watchapp
 * sends to its Android companion.
 *
 * CoreApp binds to this for a watchapp that names this app's package as its
 * companion, for as long as that app is open on the watch. It reports the
 * app opening and closing and hands over each message the app sends, and
 * all three go into [WatchInbox] for a caller to read.
 *
 * A message is acknowledged as soon as it is queued. The watch is told its
 * message reached the phone, which is all this app can know: whoever acts
 * on it reads the queue later, possibly never.
 *
 * Exported because CoreApp is another app, and open to nobody else: a
 * request from any other package is refused. With the PebbleKit switch off
 * nothing is queued and messages are refused, so the watchapp sees a NACK
 * rather than an acknowledgement nobody will honour.
 */
class PebbleKit2ListenerService : Service() {

    private val binder = object : UniversalRequestResponse.Stub() {
        override fun request(data: Bundle?, callback: SendDataCallback?) {
            val reply = handle(data, Binder.getCallingUid())
            try {
                callback?.onResult(reply)
            } catch (e: RemoteException) {
                Log.w(TAG, "The Pebble app went away before the reply", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun handle(data: Bundle?, callerUid: Int): Bundle {
        val action = data?.getString(PebbleKit2.KEY_ACTION)
        val isMessage = action == ACTION_RECEIVE_DATA
        if (packageManager.getPackagesForUid(callerUid)?.contains(Watch.COREAPP_PACKAGE) != true) {
            Log.w(TAG, "Refused a request that did not come from the Pebble app")
            return answer(isMessage, acknowledged = false)
        }
        val uuid = data?.getString(PebbleKit2.KEY_WATCHAPP_UUID)?.let(Watch::resolve)
        if (uuid == null || !Config(this).featureEnabled(PebbleTools.FEATURE)) {
            return answer(isMessage, acknowledged = false)
        }
        when (action) {
            ACTION_RECEIVE_DATA -> {
                val dictionary = data.getBundle(PebbleKit2.KEY_DATA_DICTIONARY) ?: Bundle()
                WatchInbox.put(uuid, WatchInbox.Kind.MESSAGE, PebbleKit2.toTuples(dictionary))
            }
            ACTION_APP_OPENED -> WatchInbox.put(uuid, WatchInbox.Kind.OPENED)
            ACTION_APP_CLOSED -> WatchInbox.put(uuid, WatchInbox.Kind.CLOSED)
            // Datalogging and anything newer: not something this app takes.
            else -> Log.i(TAG, "Ignored $action for $uuid")
        }
        return answer(isMessage, acknowledged = true)
    }

    /** Only a message has an answer; the rest are notifications. */
    private fun answer(isMessage: Boolean, acknowledged: Boolean): Bundle = Bundle().apply {
        if (isMessage) {
            putBundle(
                PebbleKit2.KEY_TRANSMISSION_RESULTS,
                Bundle().apply {
                    putString(PebbleKit2.KEY_RESULT_TYPE, if (acknowledged) "ACK" else "NACK")
                },
            )
        }
    }

    private companion object {
        const val TAG = "AgentBasicsMcpPk2In"
        const val ACTION_RECEIVE_DATA = "ACTION_RECEIVE_DATA_FROM_WATCH"
        const val ACTION_APP_OPENED = "APP_OPENED"
        const val ACTION_APP_CLOSED = "APP_CLOSED"
    }
}
