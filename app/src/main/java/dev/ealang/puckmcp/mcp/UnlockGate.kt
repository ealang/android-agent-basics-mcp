package dev.ealang.puckmcp.mcp

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ealang.puckmcp.UnlockGateActivity

/**
 * Fire an intent the moment the user unlocks the phone.
 *
 * The mechanism is the one thing Android guarantees about unlocking: the
 * system broadcasts ACTION_USER_PRESENT to runtime-registered receivers at
 * the instant the keyguard goes away, however it goes away — PIN, biometric,
 * or a plain swipe. Nothing about activity lifecycles is involved, which is
 * the point. Two attempts at doing this from inside a showWhenLocked
 * activity failed on the device (2026-09-05): requestDismissKeyguard's
 * callback never delivered success, and an activity already showing over
 * the keyguard gets no pause/resume cycle when the keyguard is dismissed,
 * so there was no lifecycle event left to hook.
 *
 * [UnlockGateActivity] is still launched, but only as a flare: it is the
 * only way to turn the screen on and surface the user's own unlock prompt
 * from the background. Whether that prompt appears or the user unlocks by
 * hand, the broadcast is what fires the intent.
 *
 * One pending intent at a time: a new request replaces the old one, and a
 * timeout drops it — an unlock ten minutes later should not launch
 * navigation the user has forgotten asking for.
 */
object UnlockGate {

    private const val TAG = "puck-mcp"
    private const val TIMEOUT_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var timeoutRunnable: Runnable? = null

    /** Wake the screen, prompt for unlock, and fire [pending] once unlocked. */
    fun launch(context: Context, pending: Intent) {
        val app = context.applicationContext

        // Already unlocked (racy caller): just fire.
        val km = app.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            fire(app, pending)
            return
        }

        cancel(app, "replaced by a newer request")

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.i(TAG, "unlock gate: user unlocked, firing carried intent")
                cancel(app, null)
                fire(app, pending)
            }
        }
        receiver = r
        app.registerReceiver(r, IntentFilter(Intent.ACTION_USER_PRESENT))

        val t = Runnable { cancel(app, "timed out after ${TIMEOUT_MS / 1000}s, intent dropped") }
        timeoutRunnable = t
        handler.postDelayed(t, TIMEOUT_MS)

        // The flare: wakes the screen and asks for the unlock prompt.
        UnlockGateActivity.launch(app)
    }

    private fun cancel(app: Context, reason: String?) {
        receiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered — fine.
            }
        }
        receiver = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        if (reason != null) Log.i(TAG, "unlock gate: $reason")
    }

    private fun fire(app: Context, pending: Intent) {
        try {
            app.startActivity(Intent(pending).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "unlock gate: fired ${pending.action ?: pending.component}")
        } catch (e: Exception) {
            Log.w(TAG, "unlock gate: carried intent failed to start", e)
        }
    }
}
