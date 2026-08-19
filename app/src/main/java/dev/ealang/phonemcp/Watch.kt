package dev.ealang.phonemcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

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
    private const val EXTRA_UUID = "uuid"

    /** Read-only, exported by CoreApp. The one thing here that can be read back. */
    private val STATE_URI: Uri = Uri.parse("content://com.getpebble.android.provider.basalt/state")

    /** Names for watchapps installed on the watch, so a UUID need not be quoted. */
    val KNOWN_APPS = mapOf("timer+" to "17bf83aa-88a9-459a-a6e5-60aa60e1dc07")

    private val UUID_FORMAT = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    sealed interface Result {
        /** The broadcast was sent. The watch does not report back. */
        data object Sent : Result
        data object NoCompanionApp : Result
        data object NoWatchConnected : Result
        data class UnknownApp(val given: String) : Result
    }

    fun launch(context: Context, app: String): Result = send(context, ACTION_START, app)

    fun stop(context: Context, app: String): Result = send(context, ACTION_STOP, app)

    private fun send(context: Context, action: String, app: String): Result {
        val uuid = resolve(app) ?: return Result.UnknownApp(app)
        if (!companionInstalled(context)) return Result.NoCompanionApp
        if (!watchConnected(context)) return Result.NoWatchConnected

        // Addressed to CoreApp rather than broadcast at large: nothing else
        // should see it, and an implicit broadcast is easier to lose.
        context.sendBroadcast(
            Intent(action).setPackage(COREAPP_PACKAGE).putExtra(EXTRA_UUID, uuid),
        )
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
