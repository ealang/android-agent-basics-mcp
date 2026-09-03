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

    /**
     * The browser-side search: unlike playFromSearch this returns a result
     * list without playing anything. Optional per app — an app that never
     * implemented onSearch answers with an error or silence, and either is a
     * survey finding.
     */
    fun search(context: Context, app: MediaApp, query: String): String {
        // Search is compat-protocol only — the framework MediaBrowser has no
        // search() — so this one path uses MediaBrowserCompat with its own
        // connect, instead of withBrowser's framework browser.
        var answer = "no answer from ${app.label} within ${CONNECT_TIMEOUT_MS / 1000}s"
        val latch = CountDownLatch(1)

        main.post {
            var browser: android.support.v4.media.MediaBrowserCompat? = null
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
                done("no search answer from ${app.label} within ${BROWSE_TIMEOUT_MS / 1000}s — the app likely never implemented search")
            }

            val callback = object : android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val connected = browser ?: return done("connected, then vanished")
                    connected.search(
                        query,
                        null,
                        object : android.support.v4.media.MediaBrowserCompat.SearchCallback() {
                            override fun onSearchResult(
                                q: String,
                                extras: android.os.Bundle?,
                                items: List<android.support.v4.media.MediaBrowserCompat.MediaItem>,
                            ) {
                                main.removeCallbacks(timeout)
                                done(
                                    if (items.isEmpty()) {
                                        "${app.label} answered search \"$query\" with no results"
                                    } else {
                                        describeCompatItems(app, query, items)
                                    },
                                )
                            }

                            override fun onError(q: String, extras: android.os.Bundle?) {
                                main.removeCallbacks(timeout)
                                done("${app.label} answered search \"$query\" with an error — search not supported or refused")
                            }
                        },
                    )
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
                browser = android.support.v4.media.MediaBrowserCompat(
                    context,
                    ComponentName(app.pkg, app.service),
                    callback,
                    null,
                )
                browser.connect()
                main.postDelayed(timeout, CONNECT_TIMEOUT_MS + BROWSE_TIMEOUT_MS)
            } catch (e: Exception) {
                done("connecting to ${app.label} threw: ${e.message}")
            }
        }

        if (!latch.await(CONNECT_TIMEOUT_MS + BROWSE_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "explore: search latch expired for ${app.pkg}")
        }
        return answer
    }

    private fun describeCompatItems(
        app: MediaApp,
        query: String,
        items: List<android.support.v4.media.MediaBrowserCompat.MediaItem>,
    ): String {
        val lines = items.take(MAX_CHILDREN).joinToString("\n") { item ->
            val kind = when {
                item.isBrowsable && item.isPlayable -> "dir+playable"
                item.isBrowsable -> "dir"
                item.isPlayable -> "playable"
                else -> "?"
            }
            val subtitle = item.description.subtitle?.let { " — $it" } ?: ""
            "[$kind] ${item.description.title}$subtitle\n    id: ${item.mediaId}"
        }
        val more = if (items.size > MAX_CHILDREN) "\n… and ${items.size - MAX_CHILDREN} more" else ""
        return "${app.label}, search \"$query\", ${items.size} results:\n$lines$more"
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

    /**
     * Transport controls beyond starting playback: next/previous/pause/play/
     * stop, same connect-and-read-back shape as [play].
     */
    fun command(context: Context, app: MediaApp, action: String): String =
        withBrowser(context, app) { browser, done ->
            val controller = try {
                MediaController(context, browser.sessionToken)
            } catch (e: Exception) {
                Log.w(TAG, "explore: no session from ${app.pkg}", e)
                done("${app.label} accepted the connection but exposed no usable media session")
                return@withBrowser
            }
            val tc = controller.transportControls
            // Built-ins matched loosely; anything else is a custom action id,
            // passed exactly as given — those are case-sensitive.
            when (action.trim().lowercase()) {
                "next" -> tc.skipToNext()
                "previous" -> tc.skipToPrevious()
                "pause" -> tc.pause()
                "play" -> tc.play()
                "stop" -> tc.stop()
                // The session's queue, for apps advertising skip_to_queue_item:
                // "queue" lists it, "queue:<id>" jumps to an entry.
                "queue" -> {
                    val queue = controller.queue
                    done(
                        if (queue.isNullOrEmpty()) "${app.label} exposes no queue on its session"
                        else "${app.label} queue, ${queue.size} items:\n" +
                            queue.joinToString("\n") { "  ${it.queueId}: ${it.description.title}" },
                    )
                    return@withBrowser
                }
                else -> if (action.trim().lowercase().startsWith("queue:")) {
                    val id = action.trim().substringAfter(":").trim().toLongOrNull()
                    if (id == null) {
                        done("queue:<id> needs the numeric id from the queue listing")
                        return@withBrowser
                    }
                    tc.skipToQueueItem(id)
                } else if (action.trim().lowercase().startsWith("speed:")) {
                    val speed = action.trim().substringAfter(":").trim().toFloatOrNull()
                    if (speed == null || speed <= 0f) {
                        done("speed:<multiplier> needs a positive number, e.g. speed:1.5")
                        return@withBrowser
                    }
                    tc.setPlaybackSpeed(speed)
                } else if (action.trim().lowercase().startsWith("rate:")) {
                    // Built against whatever rating scheme the session declares
                    // (heart, thumbs, stars, percentage): up/down/clear, or a
                    // number for star/percentage schemes.
                    val spec = action.trim().substringAfter(":").trim().lowercase()
                    val type = controller.ratingType
                    val rating = when {
                        spec == "clear" -> android.media.Rating.newUnratedRating(type)
                        type == android.media.Rating.RATING_HEART ->
                            android.media.Rating.newHeartRating(spec == "up")
                        type == android.media.Rating.RATING_THUMB_UP_DOWN ->
                            android.media.Rating.newThumbRating(spec == "up")
                        type in android.media.Rating.RATING_3_STARS..android.media.Rating.RATING_5_STARS ->
                            spec.toFloatOrNull()?.let { android.media.Rating.newStarRating(type, it) }
                        type == android.media.Rating.RATING_PERCENTAGE ->
                            spec.toFloatOrNull()?.let { android.media.Rating.newPercentageRating(it) }
                        else -> null
                    }
                    if (rating == null) {
                        done(
                            "this session's rating type is $type — use rate:up, rate:down, " +
                                "rate:clear, or rate:<number> for star/percentage types",
                        )
                        return@withBrowser
                    }
                    tc.setRating(rating)
                } else if (action.trim().lowercase().startsWith("seek:")) {
                    // seek:<seconds> absolute, seek:+30 / seek:-30 relative to
                    // the session's reported position.
                    val spec = action.trim().substringAfter(":").trim()
                    val amount = spec.toLongOrNull()
                    if (amount == null) {
                        done("seek:<seconds> needs a number — absolute, or +N/-N for relative")
                        return@withBrowser
                    }
                    val targetMs = if (spec.startsWith("+") || spec.startsWith("-")) {
                        val pos = controller.playbackState?.position ?: 0L
                        (pos + amount * 1000L).coerceAtLeast(0L)
                    } else {
                        amount * 1000L
                    }
                    tc.seekTo(targetMs)
                } else {
                    tc.sendCustomAction(action.trim(), null)
                }
            }
            Log.i(TAG, "explore: ${app.pkg} $action")
            main.postDelayed({ done(readBack(app, controller, action)) }, PLAY_SETTLE_MS)
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
        val position = state?.takeIf { it.position > 0 }?.let {
            val dur = try {
                controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
            } catch (e: Exception) {
                null
            }?.takeIf { d -> d > 0 }?.let { d -> " of ${d / 1000}s" } ?: ""
            " Position: ${it.position / 1000}s$dur."
        } ?: ""
        val speed = state?.playbackSpeed?.takeIf { it != 1.0f && it != 0.0f }
            ?.let { " Speed: ${it}x." } ?: ""
        val ratingType = when (controller.ratingType) {
            android.media.Rating.RATING_HEART -> " Rating type: heart."
            android.media.Rating.RATING_THUMB_UP_DOWN -> " Rating type: thumbs."
            android.media.Rating.RATING_3_STARS -> " Rating type: 3 stars."
            android.media.Rating.RATING_4_STARS -> " Rating type: 4 stars."
            android.media.Rating.RATING_5_STARS -> " Rating type: 5 stars."
            android.media.Rating.RATING_PERCENTAGE -> " Rating type: percentage."
            else -> ""
        }
        return "Sent ${app.label} $described. ${PLAY_SETTLE_MS / 1000}s later the " +
            "session reports: $stateName.$playing$position$speed$ratingType${advertised(state)}"
    }

    /**
     * What the session claims to support: the standard-action bitmask decoded
     * to names, plus any custom actions with their ids. This is the per-app
     * survey data — e.g. whether "next" being ignored is honesty (not
     * advertised) or a lie (advertised, unimplemented).
     */
    private fun advertised(state: PlaybackState?): String {
        if (state == null) return " Advertised actions: none (no playback state)."
        val names = mutableListOf<String>()
        fun has(bit: Long, name: String) {
            if (state.actions and bit != 0L) names.add(name)
        }
        has(PlaybackState.ACTION_PLAY, "play")
        has(PlaybackState.ACTION_PAUSE, "pause")
        has(PlaybackState.ACTION_PLAY_PAUSE, "play_pause")
        has(PlaybackState.ACTION_STOP, "stop")
        has(PlaybackState.ACTION_SKIP_TO_NEXT, "skip_to_next")
        has(PlaybackState.ACTION_SKIP_TO_PREVIOUS, "skip_to_previous")
        has(PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM, "skip_to_queue_item")
        has(PlaybackState.ACTION_FAST_FORWARD, "fast_forward")
        has(PlaybackState.ACTION_REWIND, "rewind")
        has(PlaybackState.ACTION_SEEK_TO, "seek_to")
        has(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID, "play_from_media_id")
        has(PlaybackState.ACTION_PLAY_FROM_SEARCH, "play_from_search")
        has(PlaybackState.ACTION_PLAY_FROM_URI, "play_from_uri")
        has(PlaybackState.ACTION_PREPARE, "prepare")
        has(PlaybackState.ACTION_SET_RATING, "set_rating")
        val custom = state.customActions.joinToString(", ") { "\"${it.action}\" (${it.name})" }
        val customPart = if (custom.isEmpty()) "" else " Custom actions: $custom."
        return " Advertised actions: ${names.joinToString(", ").ifEmpty { "none" }}.$customPart"
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
