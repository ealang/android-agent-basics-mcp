package dev.ealang.phonemcp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock

/**
 * The checks that can be made before acting, all in one place.
 *
 * Everything here is answered synchronously by the framework — is the app
 * there, do we hold the permission, is the watch connected — so running them
 * costs nothing and can be done on every call. That matters because they cover
 * the whole class of failure the user can actually fix: a missing app, a
 * permission never granted, a watch out of range. What is left after a
 * preflight passes is the request going astray somewhere we cannot see, which
 * is rare and which no amount of waiting would reveal.
 *
 * Each function returns null when there is nothing wrong, or one fixed
 * sentence naming what the user must do. The strings are written for the
 * calling model to repeat to the user, so they name the app and the Settings
 * screen and nothing else — no package names, no exception text, nothing that
 * would need sanitising before it is shown.
 */
object Preflight {

    /**
     * Opening any other app from our background service.
     *
     * Without the overlay grant Android drops the activity start in silence,
     * so this is the difference between "nothing happened" and a sentence the
     * user can act on.
     */
    private const val NO_BACKGROUND_LAUNCH =
        "the phone is blocking this app from opening other apps in the background. " +
            "Ask the user to open Phone MCP and grant \"Display over other apps\". " +
            "Nothing was done."

    fun clock(context: Context): String? = when {
        !Clock.canStartFromBackground(context) -> NO_BACKGROUND_LAUNCH
        !handles(context, Intent(AlarmClock.ACTION_SET_TIMER)) ->
            "no app on the phone handles timers. Nothing was done."
        else -> null
    }

    fun watch(context: Context): String? = when {
        !installed(context, Watch.COREAPP_PACKAGE) ->
            "the Pebble companion app is not installed on the phone. Nothing was sent."
        !Watch.connected(context) ->
            "no watch is connected. Ask the user to check it is paired, switched on, " +
                "and in range. Nothing was sent."
        else -> null
    }

    /** Starting playback: we launch Pocket Casts, and it plays under its own steam. */
    fun podcastPlayback(context: Context): String? = when {
        !installed(context, Podcast.POCKET_CASTS_PACKAGE) ->
            "Pocket Casts is not installed on the phone."
        !Clock.canStartFromBackground(context) -> NO_BACKGROUND_LAUNCH
        else -> null
    }

    /** Reading what is playing, which goes through the media session instead. */
    fun podcastState(context: Context): String? = when {
        !installed(context, Podcast.POCKET_CASTS_PACKAGE) ->
            "Pocket Casts is not installed on the phone."
        !Podcast.hasSessionAccess(context) -> NO_NOTIFICATION_ACCESS
        else -> null
    }

    private const val NO_NOTIFICATION_ACCESS =
        "this app has not been given notification access, which is what lets it see " +
            "what is playing. Ask the user to enable Phone MCP under Settings > " +
            "Notifications > Notification access."

    private const val NO_RECORDER =
        "the voice recorder app is not installed on the phone."

    /** Starting a recording is an activity launch, so the overlay grant counts. */
    fun recorderStart(context: Context): String? = when {
        !Clock.canStartFromBackground(context) -> NO_BACKGROUND_LAUNCH
        !installed(context, Recorder.PACKAGE) -> NO_RECORDER
        else -> null
    }

    /** Stopping is a broadcast, which needs no grant of ours. */
    fun recorderStop(context: Context): String? = when {
        !installed(context, Recorder.PACKAGE) -> NO_RECORDER
        else -> null
    }

    fun location(context: Context): String? = when {
        !Locator.hasPermission(context) ->
            "this app does not have location access. Ask the user to open Phone MCP " +
                "and grant location."
        !Locator.servicesEnabled(context) ->
            "location is switched off on the phone, so nothing can answer. Ask the " +
                "user to turn it on."
        else -> null
    }

    fun voice(context: Context): String? = when {
        !Clock.canStartFromBackground(context) -> NO_BACKGROUND_LAUNCH
        !installed(context, Voice.PACKAGE) ->
            "the voice gateway app is not installed on the phone."
        else -> null
    }

    /**
     * Whether [wanted] names a conversation the gateway knows about.
     *
     * Matching mirrors the gateway's own rule — a name or enough of one — so a
     * name this accepts is one it will resolve. An empty list means the gateway
     * did not answer, which is not evidence the name is wrong, so it is left
     * alone rather than turned into a refusal.
     */
    fun voiceConversation(context: Context, wanted: String): String? {
        val names = Voice.conversationNames(context)
        if (names.isEmpty()) return null
        val needle = wanted.trim().lowercase()
        if (names.any { it.lowercase().contains(needle) }) return null
        return "there is no conversation called \"$wanted\". The user has: " +
            names.joinToString(", ") + "."
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun handles(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
