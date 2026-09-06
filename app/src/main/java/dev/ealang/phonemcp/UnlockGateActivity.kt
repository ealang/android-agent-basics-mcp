package dev.ealang.phonemcp

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * The generic locked-phone gate: an invisible activity of our own that shows
 * over the keyguard — an app may always do that for *its own* activities, no
 * grant needed — asks Android to dismiss the keyguard, and fires a carried
 * intent the moment the user unlocks.
 *
 * This exists because a launch aimed at a locked phone is otherwise swallowed
 * in silence when the target does not show over the keyguard itself: the
 * activity start "succeeds", the payload is discarded (Google Maps, measured
 * 2026-09-05, `docs/maps-survey.md`). We cannot force someone else's activity
 * over the keyguard — `setShowWhenLocked` is the target's own call — but we
 * can put *our* activity there and have the system walk the user through
 * their normal PIN or biometric prompt. Unlock, and the carried intent fires
 * with the keyguard gone; ignore or fail the prompt, and it is dropped here,
 * with a log line, which is exactly what the caller was told would happen.
 *
 * Not exported, so the carried intent can only ever come from this app.
 */
class UnlockGateActivity : Activity() {

    companion object {
        private const val TAG = "phone-mcp"
        private const val EXTRA_INTENT = "pending_intent"

        /** Wake the screen, prompt for unlock, then fire [pending]. */
        fun launch(context: Context, pending: Intent) {
            context.startActivity(
                Intent(context, UnlockGateActivity::class.java)
                    .putExtra(EXTRA_INTENT, pending)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        @Suppress("DEPRECATION")
        val pending = intent.getParcelableExtra<Intent>(EXTRA_INTENT)
        if (pending == null) {
            Log.w(TAG, "unlock gate: launched with no carried intent")
            finish()
            return
        }

        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            // Unlocked by the time we came up — a race the user wins. Fire.
            fire(pending)
            finish()
            return
        }

        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    fire(pending)
                    finish()
                }

                override fun onDismissCancelled() {
                    // The user saw the prompt and declined — their answer.
                    Log.i(TAG, "unlock gate: user declined the unlock, intent dropped")
                    finish()
                }

                override fun onDismissError() {
                    Log.w(TAG, "unlock gate: keyguard dismiss failed, intent dropped")
                    finish()
                }
            },
        )
    }

    private fun fire(pending: Intent) {
        try {
            startActivity(Intent(pending).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "unlock gate: keyguard gone, fired ${pending.action ?: pending.component}")
        } catch (e: Exception) {
            Log.w(TAG, "unlock gate: carried intent failed to start", e)
        }
    }
}
