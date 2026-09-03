package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A temporary survey tool, not a feature: walks every installed app that
 * declares a MediaBrowserService and reports who lets us in. This is the
 * empirical question behind generic media control — each app's `onGetRoot`
 * decides per caller whether to accept a connection, and no API reveals the
 * policy without simply trying. Android Auto builds its app list from exactly
 * this service query.
 *
 * For each app: connect (bounded wait), and if accepted, list the root's
 * children — the top level of the same tree Auto shows. Read-only throughout;
 * nothing is played. Sequential on the main thread, one app at a time,
 * because MediaBrowser insists on a Looper and the report reads better
 * ordered anyway.
 */
class MediaProbe(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val report = StringBuilder()

    /** Main thread. [onDone] gets the full report once every app answered. */
    fun run(onDone: (String) -> Unit) {
        val services = context.packageManager.queryIntentServices(
            Intent(SERVICE_ACTION),
            0,
        )
        report.append("Apps declaring a MediaBrowserService: ${services.size}\n")

        val sessions = try {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context, MediaListenerService::class.java))
                .map { it.packageName }
        } catch (e: SecurityException) {
            null
        }
        report.append(
            when {
                sessions == null -> "Active sessions: unreadable (no notification access)\n"
                sessions.isEmpty() -> "Active sessions: none right now\n"
                else -> "Active sessions: ${sessions.joinToString()}\n"
            },
        )

        val queue = services.mapNotNull { resolved ->
            val info = resolved.serviceInfo ?: return@mapNotNull null
            val label = info.applicationInfo.loadLabel(context.packageManager)
            Triple(label.toString(), info.packageName, info.name)
        }
        probeNext(queue, onDone)
    }

    private fun probeNext(queue: List<Triple<String, String, String>>, onDone: (String) -> Unit) {
        val next = queue.firstOrNull()
        if (next == null) {
            onDone(report.toString())
            return
        }
        val (label, pkg, cls) = next
        report.append("\n== $label ($pkg)\n")
        probeOne(pkg, cls) { probeNext(queue.drop(1), onDone) }
    }

    /**
     * One connect attempt. Every exit path is funneled through [finish] with
     * a latch, because MediaBrowser can answer on its own schedule after our
     * timeout has already moved the sweep along.
     */
    private fun probeOne(pkg: String, cls: String, onFinished: () -> Unit) {
        var browser: MediaBrowser? = null
        var finished = false

        fun finish(line: String) {
            if (finished) return
            finished = true
            report.append(line)
            try {
                browser?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "probe: disconnect $pkg", e)
            }
            onFinished()
        }

        val timeout = Runnable { finish("no answer within ${STEP_TIMEOUT_MS / 1000}s\n") }

        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                main.removeCallbacks(timeout)
                val connected = browser ?: return finish("connected, then vanished\n")
                val root = try {
                    connected.root
                } catch (e: Exception) {
                    return finish("CONNECTED but root unreadable: ${e.message}\n")
                }
                report.append("CONNECTED, root \"$root\"\n")
                listChildren(connected, root, ::finish)
            }

            override fun onConnectionFailed() {
                main.removeCallbacks(timeout)
                finish("REFUSED (onGetRoot said no — likely a signature allowlist)\n")
            }

            override fun onConnectionSuspended() {
                main.removeCallbacks(timeout)
                finish("connection suspended\n")
            }
        }

        try {
            browser = MediaBrowser(context, ComponentName(pkg, cls), callback, null)
            browser.connect()
            main.postDelayed(timeout, STEP_TIMEOUT_MS)
        } catch (e: Exception) {
            finish("connect threw: ${e.message}\n")
        }
    }

    /**
     * The root's children — the top level of the Auto-style tree. An app can
     * also accept the connection but hand strangers an empty root; seeing
     * that here is exactly the "degraded access" tier worth knowing about.
     */
    private fun listChildren(browser: MediaBrowser, root: String, finish: (String) -> Unit) {
        var answered = false
        val timeout = Runnable {
            if (!answered) {
                answered = true
                finish("children: no answer within ${STEP_TIMEOUT_MS / 1000}s\n")
            }
        }
        main.postDelayed(timeout, STEP_TIMEOUT_MS)
        browser.subscribe(
            root,
            object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                    if (answered) return
                    answered = true
                    main.removeCallbacks(timeout)
                    val lines = children.take(MAX_CHILDREN).joinToString("") { item ->
                        val kind = when {
                            item.isBrowsable && item.isPlayable -> "dir+playable"
                            item.isBrowsable -> "dir"
                            item.isPlayable -> "playable"
                            else -> "?"
                        }
                        "  [$kind] ${item.description.title} (${item.mediaId})\n"
                    }
                    val more = if (children.size > MAX_CHILDREN) {
                        "  … and ${children.size - MAX_CHILDREN} more\n"
                    } else {
                        ""
                    }
                    finish(
                        if (children.isEmpty()) "children: empty (browse hidden from us)\n"
                        else "children: ${children.size}\n$lines$more",
                    )
                }

                override fun onError(parentId: String) {
                    if (answered) return
                    answered = true
                    main.removeCallbacks(timeout)
                    finish("children: browse error\n")
                }
            },
        )
    }

    companion object {
        private const val TAG = "phone-mcp"

        /** The action Auto and Wear query for; compat services register it too. */
        const val SERVICE_ACTION = "android.media.browse.MediaBrowserService"

        private const val STEP_TIMEOUT_MS = 4_000L
        private const val MAX_CHILDREN = 12
    }
}
