package dev.ealang.phonemcp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Experimental, like [MediaProbe]: the generic media surface, exposed as MCP
 * tools so the survey can be driven remotely — walk any app's browse tree,
 * then try to actually start playback, one app at a time, and see what
 * happens on the device. What survives into a real `play_media` design gets
 * decided by what these calls turn up.
 *
 * Everything here binds the target app's MediaBrowserService. That is the
 * Android Auto route: connecting wakes the app's process without any
 * activity, no keyguard involvement, and hands back both a browse tree and
 * the app's media session. Whether an app accepts the connection, shows
 * strangers its tree, or honors a play command are three separate per-app
 * policies, which is exactly what the exploring measures.
 *
 * Threading: MediaBrowser demands a Looper, so all work runs on the main
 * handler; the server thread blocks on a latch like the other integrations.
 * Play verification reads the session's playback state after a short wait —
 * long enough to catch "the command was ignored", not a poll loop.
 */
object MediaExplorer {

    private const val TAG = "phone-mcp"

    private const val CONNECT_TIMEOUT_MS = 6_000L
    private const val BROWSE_TIMEOUT_MS = 6_000L

    /** How long a play command gets before we read back the session state. */
    private const val PLAY_SETTLE_MS = 3_000L

    private const val MAX_CHILDREN = 40

    private val main = Handler(Looper.getMainLooper())

    data class MediaApp(val label: String, val pkg: String, val service: String)

    /** Every installed app declaring a MediaBrowserService, Auto's own query. */
    fun mediaApps(context: Context): List<MediaApp> =
        context.packageManager
            .queryIntentServices(Intent(MediaProbe.SERVICE_ACTION), 0)
            .mapNotNull { resolved ->
                val info = resolved.serviceInfo ?: return@mapNotNull null
                MediaApp(
                    label = info.applicationInfo.loadLabel(context.packageManager).toString(),
                    pkg = info.packageName,
                    service = info.name,
                )
            }

    /**
     * A name as the caller typed it into the app that declared it. Substring
     * match on label or package, case-insensitive; ambiguity and no-match both
     * come back as the message to show, with the candidates listed so the next
     * call can succeed.
     */
    fun resolve(context: Context, given: String): Pair<MediaApp?, String?> {
        val apps = mediaApps(context)
        if (apps.isEmpty()) return null to "no installed app declares a media browser service"
        val needle = given.trim().lowercase()
        if (needle.isEmpty()) return null to "give an app name. Installed: ${names(apps)}"
        val matches = apps.filter {
            it.label.lowercase().contains(needle) || it.pkg.lowercase().contains(needle)
        }
        return when {
            matches.size == 1 -> matches.first() to null
            matches.isEmpty() -> null to "no media app matches \"$given\". Installed: ${names(apps)}"
            else -> null to "\"$given\" is ambiguous: ${names(matches)}"
        }
    }

    private fun names(apps: List<MediaApp>) = apps.joinToString(", ") { it.label }

    /**
     * Connect and list one node's children — the root when [nodeId] is null.
     * Returns prose either way; there is no failure shape, because for a
     * survey tool "REFUSED" is a finding, not an error.
     */
    fun browse(context: Context, app: MediaApp, nodeId: String?): String =
        withBrowser(context, app) { browser, done ->
            val node = nodeId ?: browser.root
            val timeout = Runnable {
                done("connected, but no children answer for \"$node\" within ${BROWSE_TIMEOUT_MS / 1000}s")
            }
            main.postDelayed(timeout, BROWSE_TIMEOUT_MS)
            browser.subscribe(
                node,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                        main.removeCallbacks(timeout)
                        done(describeChildren(app, node, children))
                    }

                    override fun onError(parentId: String) {
                        main.removeCallbacks(timeout)
                        done("${app.label} answered \"$node\" with a browse error")
                    }
                },
            )
        }

    private fun describeChildren(app: MediaApp, node: String, children: List<MediaBrowser.MediaItem>): String {
        if (children.isEmpty()) {
            return "${app.label}, node \"$node\": no children — either an empty " +
                "folder or browsing withheld from strangers"
        }
        val lines = children.take(MAX_CHILDREN).joinToString("\n") { item ->
            val kind = when {
                item.isBrowsable && item.isPlayable -> "dir+playable"
                item.isBrowsable -> "dir"
                item.isPlayable -> "playable"
                else -> "?"
            }
            val subtitle = item.description.subtitle?.let { " — $it" } ?: ""
            "[$kind] ${item.description.title}$subtitle\n    id: ${item.mediaId}"
        }
        val more = if (children.size > MAX_CHILDREN) {
            "\n… and ${children.size - MAX_CHILDREN} more"
        } else {
            ""
        }
        return "${app.label}, node \"$node\", ${children.size} children:\n$lines$more"
    }

    /**
     * Try to start playback: a specific item by [mediaId], the app's own
     * match for [query], or a bare resume when both are null. Issues the
     * command through the session the browser connection exposes, waits a
     * beat, and reads the playback state back — the closest thing to an
     * answer this API offers, since the command itself is fire-and-forget.
     */
    fun play(context: Context, app: MediaApp, mediaId: String?, query: String?): String =
        withBrowser(context, app) { browser, done ->
            val controller = try {
                MediaController(context, browser.sessionToken)
            } catch (e: Exception) {
                Log.w(TAG, "explore: no session from ${app.pkg}", e)
                done("${app.label} accepted the connection but exposed no usable media session")
                return@withBrowser
            }
            val (what, described) = when {
                mediaId != null -> {
                    controller.transportControls.playFromMediaId(mediaId, null)
                    "playFromMediaId(\"$mediaId\")" to "item \"$mediaId\""
                }
                query != null -> {
                    controller.transportControls.playFromSearch(query, null)
                    "playFromSearch(\"$query\")" to "search \"$query\""
                }
                else -> {
                    controller.transportControls.play()
                    "play()" to "resume"
                }
            }
            Log.i(TAG, "explore: ${app.pkg} $what")
            main.postDelayed({ done(readBack(app, controller, described)) }, PLAY_SETTLE_MS)
        }

    private fun readBack(app: MediaApp, controller: MediaController, described: String): String {
        val state = try {
            controller.playbackState
        } catch (e: Exception) {
            null
        }
        val title = try {
            controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        } catch (e: Exception) {
            null
        }
        val stateName = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_CONNECTING -> "connecting"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_ERROR -> "error: ${state.errorMessage ?: "unspecified"}"
            PlaybackState.STATE_NONE, null -> "no state — the command was likely ignored"
            else -> "state ${state.state}"
        }
        val playing = title?.let { " Now loaded: \"$it\"." } ?: ""
        return "Sent ${app.label} $described. ${PLAY_SETTLE_MS / 1000}s later the " +
            "session reports: $stateName.$playing"
    }

    /**
     * Connect on the main thread, run [block] once connected, and block the
     * calling (server) thread until [block] answers or the timeout lands.
     * Every path disconnects; every path answers exactly once.
     */
    private fun withBrowser(
        context: Context,
        app: MediaApp,
        block: (MediaBrowser, done: (String) -> Unit) -> Unit,
    ): String {
        var answer = "no answer within ${CONNECT_TIMEOUT_MS / 1000}s — the app never responded"
        val latch = CountDownLatch(1)

        main.post {
            var browser: MediaBrowser? = null
            var finished = false

            fun done(text: String) {
                if (finished) return
                finished = true
                answer = text
                try {
                    browser?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "explore: disconnect ${app.pkg}", e)
                }
                latch.countDown()
            }

            val timeout = Runnable {
                done("no answer from ${app.label} within ${CONNECT_TIMEOUT_MS / 1000}s")
            }

            val callback = object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    main.removeCallbacks(timeout)
                    val connected = browser ?: return done("connected, then vanished")
                    try {
                        block(connected, ::done)
                    } catch (e: Exception) {
                        Log.w(TAG, "explore: ${app.pkg}", e)
                        done("${app.label} answered, then threw: ${e.message}")
                    }
                }

                override fun onConnectionFailed() {
                    main.removeCallbacks(timeout)
                    done("${app.label} REFUSED the connection (its onGetRoot said no)")
                }

                override fun onConnectionSuspended() {
                    main.removeCallbacks(timeout)
                    done("${app.label} suspended the connection")
                }
            }

            try {
                browser = MediaBrowser(context, ComponentName(app.pkg, app.service), callback, null)
                browser.connect()
                main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                done("connecting to ${app.label} threw: ${e.message}")
            }
        }

        // Generous outer bound: the inner timeouts answer first in any normal
        // run; this only guards against a path that somehow never calls done.
        if (!latch.await(CONNECT_TIMEOUT_MS + BROWSE_TIMEOUT_MS + PLAY_SETTLE_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "explore: latch expired for ${app.pkg}")
        }
        return answer
    }
}
