package dev.ealang.puckmcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the server back after a reboot or an app update.
 *
 * BOOT_COMPLETED only reaches an app that the user has launched at least once
 * since install, and OEM battery managers can suppress it — so this is a best
 * effort, and the UI is the authority on whether the server is actually up.
 *
 * Timers need no attention here: they belong to the clock app, which restores
 * its own.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("PuckMcpBoot", "Received ${intent.action}")
        Logs.init(context.filesDir)
        Logs.mcp.info("broadcast", intent.action)

        if (Config(context).enabled) {
            McpService.start(context)
        }
    }
}
