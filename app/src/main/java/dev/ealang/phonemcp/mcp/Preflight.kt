package dev.ealang.phonemcp.mcp

import android.content.Context
import android.content.pm.PackageManager
import dev.ealang.phonemcp.mcp.apps.Clock
import dev.ealang.phonemcp.mcp.apps.Locator
import dev.ealang.phonemcp.mcp.apps.Podcast
import dev.ealang.phonemcp.mcp.pebble.Watch

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

    /** Any activity start on someone else's behalf needs the overlay grant. */
    fun activityLaunch(context: Context): String? =
        if (!Clock.canStartFromBackground(context)) NO_BACKGROUND_LAUNCH else null

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

    fun location(context: Context): String? = when {
        !Locator.hasPermission(context) ->
            "this app does not have location access. Ask the user to open Phone MCP " +
                "and grant location."
        !Locator.servicesEnabled(context) ->
            "location is switched off on the phone, so nothing can answer. Ask the " +
                "user to turn it on."
        else -> null
    }

    /**
     * areNotificationsEnabled covers both ways posting can be off: the
     * POST_NOTIFICATIONS runtime grant never given, and the user switching
     * the app off in Settings later.
     */
    fun notifications(context: Context): String? =
        if (!context.getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled()
        ) {
            "this app is not allowed to post notifications. Ask the user to open " +
                "Phone MCP and allow notifications."
        } else {
            null
        }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

}
