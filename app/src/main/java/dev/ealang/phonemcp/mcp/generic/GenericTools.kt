package dev.ealang.phonemcp.mcp.generic

import android.content.Context
import dev.ealang.phonemcp.mcp.Preflight
import dev.ealang.phonemcp.mcp.ToolSpec
import dev.ealang.phonemcp.mcp.blocked
import org.json.JSONArray
import org.json.JSONObject

/**
 * The advanced generic tier (CLAUDE.md's second doctrine): raw Android
 * capabilities, remoted. They execute exactly what they are told — no guards
 * beyond our own grants. Experimental prototypes; findings over polish.
 */
object GenericTools {

    fun specs(context: Context): List<ToolSpec> {
        val mediaApps = ToolSpec(
            name = "media_apps",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_apps")
                .put("description", "EXPERIMENTAL: list installed apps declaring a MediaBrowserService.")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())),
        ) { _ ->
            val apps = MediaExplorer.mediaApps(context)
            if (apps.isEmpty()) "No installed app declares a MediaBrowserService." to false
            else apps.joinToString("\n") { "${it.label} (${it.pkg})" } to false
        }

        val mediaBrowse = ToolSpec(
            name = "media_browse",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_browse")
                .put("description", "EXPERIMENTAL: list one node of a media app's browse tree (root if no node given).")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                            .put("node", JSONObject().put("type", "string").put("description", "Media id of the node to list."))
                            .put("offset", JSONObject().put("type", "integer").put("description", "Skip this many children — for paging long listings.")),
                    ).put("required", JSONArray().put("app")),
                ),
        ) { args ->
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            if (app == null) "FAILED: $problem" to true
            else MediaExplorer.browse(
                context,
                app,
                args.optString("node").ifBlank { null },
                args.optInt("offset", 0).coerceAtLeast(0),
            ) to false
        }

        val mediaPlay = ToolSpec(
            name = "media_play",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_play")
                .put("description", "EXPERIMENTAL: try to start playback in a media app — by media_id, by search query, or bare resume. Reads back the session state after a beat. Audible on the phone.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                            .put("media_id", JSONObject().put("type", "string").put("description", "A playable id from media_browse."))
                            .put("query", JSONObject().put("type", "string").put("description", "Free-text search the app resolves itself.")),
                    ).put("required", JSONArray().put("app")),
                ),
        ) { args ->
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            if (app == null) {
                "FAILED: $problem" to true
            } else {
                MediaExplorer.play(
                    context,
                    app,
                    args.optString("media_id").ifBlank { null },
                    args.optString("query").ifBlank { null },
                ) to false
            }
        }

        val mediaCommand = ToolSpec(
            name = "media_command",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_command")
                .put("description", "EXPERIMENTAL: send a media app a transport command: next, previous, pause, play, stop, queue, queue:<id>, seek:<seconds> (absolute, or +N/-N relative), speed:<multiplier>, rate:up/down/clear/<number>, uri:<uri>, search:<query>, media:<id>, queue_add:<id>, queue_add:<pos>:<id>, queue_remove:<id>, or a custom action id verbatim. Reads back the session state, position, and advertised actions after a beat.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                            .put("action", JSONObject().put("type", "string").put("description", "next, previous, pause, play, stop, queue, queue:<id>, seek:<seconds>, seek:+N, seek:-N, speed:<multiplier>, rate:up/down/clear/<number>, uri:<uri>, search:<query>, media:<id>, queue_add:<id>, queue_add:<pos>:<id>, queue_remove:<id>, or a custom action id."))
                            .put("route", JSONObject().put("type", "string").put("description", "browser (default) connects the app's MediaBrowserService. session drives the app's live media session instead — reaches apps whose browser service refuses the connection, but only while they are playing or paused, and cannot cold-start anything. Needs notification access. Queue edits are browser-only.")),
                    ).put("required", JSONArray().put("app").put("action")),
                ),
        ) { args ->
            val action = args.optString("action")
            when (val route = args.optString("route").trim().lowercase().ifEmpty { "browser" }) {
                "session" -> {
                    val text = SessionExplorer.command(context, args.optString("app"), action)
                    text to text.startsWith("FAILED:")
                }
                "browser" -> {
                    val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
                    if (app == null) "FAILED: $problem" to true
                    // Queue edits ride the compat protocol, so they take a different
                    // connection path from the rest of the commands.
                    else if (action.trim().startsWith("queue_add:") || action.trim().startsWith("queue_remove:")) {
                        MediaExplorer.queueEdit(context, app, action.trim()) to false
                    } else {
                        MediaExplorer.command(context, app, action) to false
                    }
                }
                else -> "FAILED: route must be browser or session, not \"$route\"." to true
            }
        }

        val mediaSessions = ToolSpec(
            name = "media_sessions",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_sessions")
                .put("description", "EXPERIMENTAL: list the phone's active media sessions — every app currently playing or paused-but-alive, whatever it is, with state, title, position, and advertised actions. Sees apps media_browse cannot connect to. Needs notification access.")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())),
        ) { _ ->
            val text = SessionExplorer.list(context)
            text to text.startsWith("FAILED:")
        }

        val mediaKey = ToolSpec(
            name = "media_key",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_key")
                .put("description", "EXPERIMENTAL: press a system media button — the mechanism a car or headset uses. Takes no target: Android routes the press to the most recent media app, which may restart that app even when it is fully closed. Keys: play, pause, play_pause, next, previous, stop, fast_forward, rewind. Not all apps respect the press — use the app-specific skill file when one exists. Reads back the active sessions after a beat. Audible on the phone.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject().put("key", JSONObject().put("type", "string").put("description", "play, pause, play_pause, next, previous, stop, fast_forward, or rewind.")),
                    ).put("required", JSONArray().put("key")),
                ),
        ) { args ->
            val text = SessionExplorer.key(context, args.optString("key"))
            text to text.startsWith("FAILED:")
        }

        val mediaSearch = ToolSpec(
            name = "media_search",
            featureKey = "media_control",
            definition = JSONObject()
                .put("name", "media_search")
                .put("description", "EXPERIMENTAL: search a media app's catalog and list the results without playing anything. Optional per app — some never implemented it.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("app", JSONObject().put("type", "string").put("description", "App name or package, fuzzy matched."))
                            .put("query", JSONObject().put("type", "string").put("description", "Free-text search.")),
                    ).put("required", JSONArray().put("app").put("query")),
                ),
        ) { args ->
            val (app, problem) = MediaExplorer.resolve(context, args.optString("app"))
            if (app == null) "FAILED: $problem" to true
            else MediaExplorer.search(context, app, args.optString("query")) to false
        }

        val intentSpecProperties = JSONObject()
            .put("action", JSONObject().put("type", "string").put("description", "Intent action, e.g. android.intent.action.VIEW."))
            .put("data", JSONObject().put("type", "string").put("description", "Data URI, e.g. https://... or geo:..."))
            .put("mime_type", JSONObject().put("type", "string").put("description", "MIME type of the data."))
            .put("categories", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Intent categories."))
            .put("package", JSONObject().put("type", "string").put("description", "Limit to this package without naming a component."))
            .put("component", JSONObject().put("type", "string").put("description", "Explicit target, \"package/ClassName\" (leading-dot class ok)."))
            .put(
                "extras",
                JSONObject().put("type", "array").put(
                    "items",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("key", JSONObject().put("type", "string"))
                            .put("type", JSONObject().put("type", "string").put("description", "string, int, long, boolean, float, double, uri, string_array, int_array, long_array, or key_event. key_event builds a KeyEvent parcelable (the payload a MEDIA_BUTTON intent needs): value is a key name — play, pause, play_pause, next, previous, stop, fast_forward, rewind, or any android KEYCODE_* name — with an optional \"up:\" prefix for ACTION_UP (default is ACTION_DOWN)."))
                            .put("value", JSONObject().put("description", "The value, matching the type.")),
                    ).put("required", JSONArray().put("key").put("value")),
                ).put("description", "Typed extras. The type must match what the receiving app reads."),
            )

        val intentScan = ToolSpec(
            name = "intent_scan",
            featureKey = "intents",
            definition = JSONObject()
                .put("name", "intent_scan")
                .put("description", "EXPERIMENTAL: scan an app's automation surface — its exported activities, receivers, and services with their intent filters and guarding permissions. With no package, lists every installed package.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject().put("package", JSONObject().put("type", "string").put("description", "Exact package name to scan; omit to list all packages.")),
                    ),
                ),
        ) { args ->
            val pkg = args.optString("package").trim()
            val text = if (pkg.isEmpty()) IntentExplorer.listPackages(context) else IntentExplorer.scan(context, pkg)
            text to text.startsWith("FAILED:")
        }

        val intentResolve = ToolSpec(
            name = "intent_resolve",
            featureKey = "intents",
            definition = JSONObject()
                .put("name", "intent_resolve")
                .put("description", "EXPERIMENTAL: dry run — list which activities, receivers, and services would handle an intent, without sending it.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", intentSpecProperties),
                ),
        ) { args ->
            val text = IntentExplorer.resolve(context, args)
            text to text.startsWith("FAILED:")
        }

        val sendIntent = ToolSpec(
            name = "send_intent",
            featureKey = "intents",
            definition = JSONObject()
                .put("name", "send_intent")
                .put("description", "EXPERIMENTAL: build and fire an Android intent — start an activity (opens on screen), send a broadcast, or start a service. Resolution is checked first; an intent nothing handles is not sent.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject(intentSpecProperties.toString())
                            .put("mode", JSONObject().put("type", "string").put("description", "activity (default), broadcast, service, or foreground_service."))
                            .put("via_unlock_gate", JSONObject().put("type", "boolean").put("description", "Activity mode only, default false. Fallback for intents that require the phone to be unlocked: if the phone is locked, wake it with the unlock prompt and start the activity when the user unlocks (expires after two minutes). Without it, a locked phone may silently discard the launch while reporting success.")),
                    ),
                ),
        ) { args ->
            val text = IntentExplorer.send(context, args)
            text to text.startsWith("FAILED:")
        }

        val providerQuery = ToolSpec(
            name = "provider_query",
            featureKey = "content_providers",
            definition = JSONObject()
                .put("name", "provider_query")
                .put("description", "EXPERIMENTAL: read another app's state through its ContentProvider. With no uri, lists every exported provider on the device with its authority and permission guards. With a content:// uri, runs the query and returns rows as text.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("uri", JSONObject().put("type", "string").put("description", "content://<authority>/<path>. Omit to list all exported providers."))
                            .put("projection", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Columns to return; omit for all."))
                            .put("selection", JSONObject().put("type", "string").put("description", "SQL-style WHERE clause, ? placeholders allowed."))
                            .put("selection_args", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Values for the ? placeholders."))
                            .put("sort_order", JSONObject().put("type", "string").put("description", "SQL-style ORDER BY clause."))
                            .put("limit", JSONObject().put("type", "integer").put("description", "Max rows to show, default 50, cap 500.")),
                    ),
                ),
        ) { args ->
            val text = ProviderExplorer.query(context, args)
            text to text.startsWith("FAILED:")
        }

        val providerCall = ToolSpec(
            name = "provider_call",
            featureKey = "content_providers",
            definition = JSONObject()
                .put("name", "provider_call")
                .put("description", "EXPERIMENTAL: invoke a method on another app's ContentProvider (ContentResolver.call) — the RPC half of the provider interface, one round trip in and out. Returns the result Bundle as text; a null Bundle is a normal answer, not a failure.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("authority", JSONObject().put("type", "string").put("description", "The provider's authority, bare, no path — e.g. com.example.app.provider. Find it with provider_query's list."))
                            .put("method", JSONObject().put("type", "string").put("description", "Method name the provider dispatches on."))
                            .put("arg", JSONObject().put("type", "string").put("description", "Optional string argument."))
                            .put("extras", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")).put("description", "Optional typed extras, same format as send_intent: [{key, type, value}] with type one of string, int, long, boolean, float, double, uri, string_array, int_array, long_array.")),
                    ).put("required", JSONArray().put("authority").put("method")),
                ),
        ) { args ->
            val text = ProviderExplorer.call(context, args)
            text to text.startsWith("FAILED:")
        }

        val postNotification = ToolSpec(
            name = "post_notification",
            featureKey = "notifications",
            definition = JSONObject()
                .put("name", "post_notification")
                .put("description", "Show a text notification in the phone's notification shade. Posting again with the same key replaces that notification: silently at the same level, with a sound if raised to alert. Tapping it opens the Phone MCP app.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("key", JSONObject().put("type", "string").put("description", "Identifies this notification. Reuse it to update or dismiss later."))
                            .put("title", JSONObject().put("type", "string"))
                            .put("text", JSONObject().put("type", "string"))
                            .put("level", JSONObject().put("type", "string").put("description", "info (default) appears silently; alert makes a sound. Neither pops up over the screen.")),
                    ).put("required", JSONArray().put("key").put("title").put("text")),
                ),
        ) { args ->
            blocked(Preflight.notifications(context)) ?: run {
                val key = args.optString("key").trim()
                val level = args.optString("level").trim().ifEmpty { "info" }
                when {
                    key.isEmpty() -> "FAILED: key must not be empty." to true
                    level != "info" && level != "alert" ->
                        "FAILED: level must be info or alert." to true
                    else -> Notifier.post(
                        context,
                        key,
                        args.optString("title"),
                        args.optString("text"),
                        alert = level == "alert",
                    )
                }
            }
        }

        val dismissNotification = ToolSpec(
            name = "dismiss_notification",
            featureKey = "notifications",
            definition = JSONObject()
                .put("name", "dismiss_notification")
                .put("description", "Remove a notification posted with post_notification, by its key.")
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject().put("key", JSONObject().put("type", "string").put("description", "The key it was posted with.")),
                    ).put("required", JSONArray().put("key")),
                ),
        ) { args ->
            Notifier.dismiss(context, args.optString("key").trim())
        }

        return listOf(
            mediaApps, mediaBrowse, mediaPlay, mediaCommand, mediaSearch,
            mediaSessions, mediaKey,
            intentScan, intentResolve, sendIntent, providerQuery, providerCall,
            postNotification, dismissNotification,
        )
    }
}
