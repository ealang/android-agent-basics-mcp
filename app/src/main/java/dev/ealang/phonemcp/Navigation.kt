package dev.ealang.phonemcp

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Turn-by-turn navigation, via Google Maps' documented `google.navigation:`
 * URI — a VIEW intent that starts routing immediately, no confirmation tap.
 *
 * Fire-and-forget by design: the intent has no return path, and none is
 * needed — Maps opening full-screen with a route (or without one) is the
 * loudest possible read-back, so a silent failure is cheap. The one silent
 * failure that isn't cheap — the keyguard discarding the destination while
 * appearing to work (`docs/maps-survey.md`) — is routed through
 * [UnlockGate] instead: the phone wakes with the user's own unlock
 * prompt, and the intent fires the moment they pass it.
 */
object Navigation {

    private const val TAG = "phone-mcp"

    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Spoken-word modes to the URI's letters. Driving is Maps' own default. */
    private val MODES = mapOf(
        "drive" to "d", "driving" to "d", "car" to "d",
        "walk" to "w", "walking" to "w",
        "bike" to "b", "biking" to "b", "cycling" to "b", "bicycle" to "b",
    )

    /**
     * Start navigating. [destination] is whatever Maps can geocode — a place
     * name, an address, "lat,long", or the user's saved "home" or "work".
     * Returns the sentence for the caller; preflight has already passed.
     */
    fun navigate(context: Context, destination: String, mode: String?): String {
        val modeLetter = mode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            MODES[it] ?: return "FAILED: mode must be drive, walk, or bike."
        }
        val uri = "google.navigation:q=" + Uri.encode(destination) +
            (modeLetter?.let { "&mode=$it" } ?: "")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked) {
            // Straight at a locked phone, Maps swallows the destination in
            // silence. Through the gate, the phone wakes with the unlock
            // prompt and the intent fires the moment the user passes it.
            UnlockGate.launch(context, intent)
            Log.i(TAG, "navigate (via unlock gate): $uri")
            return "The phone is locked, so it is now showing the unlock prompt. " +
                "Navigation to \"$destination\" starts the moment the user unlocks; " +
                "if they leave the phone locked instead, the request expires after " +
                "two minutes and nothing happens."
        }

        context.startActivity(intent)
        Log.i(TAG, "navigate: $uri")
        return "Navigation to \"$destination\" started in Google Maps" +
            (modeLetter?.let { ", ${modeName(it)} mode" } ?: "") + "."
    }

    private fun modeName(letter: String) = when (letter) {
        "w" -> "walking"
        "b" -> "biking"
        else -> "driving"
    }
}
