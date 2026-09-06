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
 * The visible half of [UnlockGate]: an invisible activity whose only job is
 * to wake the screen and put the user's own unlock prompt up. An app may
 * always show *its own* activity over the keyguard — no grant needed — which
 * is the one lever we have for getting a locked, dark phone to ask its owner
 * for a PIN or fingerprint.
 *
 * It carries nothing and fires nothing. The pending intent lives in
 * [UnlockGate], which fires it on the ACTION_USER_PRESENT broadcast — the
 * one signal Android guarantees at unlock. Both earlier designs that made
 * this activity responsible for firing failed on the device (2026-09-05):
 * requestDismissKeyguard's success callback never came, and an activity
 * already showing over the keyguard gets no lifecycle event when the
 * keyguard is dismissed, so there was nothing left to hook. requestDismiss-
 * Keyguard is still called, but purely as UX — it is what makes the PIN pane
 * appear immediately instead of the user having to swipe first.
 */
class UnlockGateActivity : Activity() {

    companion object {
        private const val TAG = "phone-mcp"

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, UnlockGateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private var dismissRequested = false

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
    }

    override fun onResume() {
        super.onResume()
        if (dismissRequested) return
        dismissRequested = true
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (!km.isKeyguardLocked) {
            finish()
            return
        }
        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    finish()
                }

                override fun onDismissCancelled() {
                    finish()
                }

                override fun onDismissError() {
                    // Stay up; the user can still unlock by hand and the
                    // USER_PRESENT receiver does not care how they did it.
                    Log.w(TAG, "unlock gate: keyguard dismiss request errored")
                }
            },
        )
    }

    /** Anything taking the foreground — the fired intent, a screen-off — ends the flare. */
    override fun onStop() {
        super.onStop()
        finish()
    }
}
