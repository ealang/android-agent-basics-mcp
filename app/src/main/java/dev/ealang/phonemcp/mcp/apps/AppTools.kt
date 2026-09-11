package dev.ealang.phonemcp.mcp.apps

import android.content.Context
import android.util.Log
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.mcp.Preflight
import dev.ealang.phonemcp.mcp.ToolSpec
import dev.ealang.phonemcp.mcp.blocked
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * The app-specific tier (CLAUDE.md's first doctrine): polished tools over
 * apps on the phone, written for a weak model — full preflight, one code
 * path, `FAILED:` with the fix named. Everything here that talks to another
 * app starts with a [Preflight] check via [blocked].
 */
object AppTools {

    private const val TAG = "PhoneMcpServer"

    fun specs(context: Context): List<ToolSpec> {
        val setTimer = ToolSpec(
            name = "set_timer",
            featureKey = "timers",
            definition = JSONObject()
                .put("name", "set_timer")
                .put(
                    "description",
                    "Start a timer in the clock app on the user's phone. It rings " +
                        "with their usual alarm sound and appears alongside any timers " +
                        "they set by hand. Use for 'remind me in N minutes' and " +
                        "kitchen-timer style requests.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "seconds",
                                    JSONObject().put("type", "integer")
                                        .put(
                                            "description",
                                            "How long from now, in seconds. Give this or at.",
                                        ),
                                )
                                .put(
                                    "at",
                                    JSONObject().put("type", "string")
                                        .put(
                                            "description",
                                            "Ring at a clock time instead: 15:30, 3pm, " +
                                                "3:30pm, noon, or midnight. Means the next " +
                                                "time that comes round.",
                                        ),
                                )
                                .put(
                                    "label",
                                    JSONObject().put("type", "string")
                                        .put(
                                            "description",
                                            "What the timer is for. Shown on the phone, and " +
                                                "the only way to cancel this specific timer later.",
                                        ),
                                ),
                        ),
                ),
        ) { args ->
            blocked(Preflight.clock(context)) ?: run {
                val label = args.optString("label").ifBlank { "Timer" }
                val at = args.optString("at").trim()
                val now = ZonedDateTime.now()
                val clockTime = if (at.isEmpty()) null else TimeOfDay.resolve(at, now)
                // Clamped rather than rejected: a clock time can only ever land a
                // little outside the range, and refusing "at 15:31" one second past
                // 15:30 would be a failure the user cannot make sense of.
                val seconds = clockTime?.seconds?.coerceIn(1L, Clock.MAX_SECONDS)
                    ?: args.optLong("seconds", -1)
                when {
                    at.isNotEmpty() && clockTime == null ->
                        "FAILED: \"$at\" is not a clock time. Use something like 15:30, " +
                            "3pm, or noon." to true
                    seconds <= 0 ->
                        "FAILED: give either seconds or at." to true
                    seconds > Clock.MAX_SECONDS ->
                        "FAILED: that is longer than 24 hours, which is the most a timer " +
                            "can be. Set an alarm or a calendar reminder instead." to true
                    !Clock.setTimer(context, seconds, label) -> clockRefused()
                    else -> {
                        Log.i(TAG, "set_timer ${Clock.humanize(seconds)} \"$label\"")
                        "The phone's clock app is now running a ${Clock.humanize(seconds)} " +
                            "timer labelled \"$label\"" + dueAt(clockTime) + ". It will ring " +
                            "even if this connection closes." to false
                    }
                }
            }
        }

        val cancelTimer = ToolSpec(
            name = "cancel_timer",
            featureKey = "timers",
            definition = JSONObject()
                .put("name", "cancel_timer")
                .put(
                    "description",
                    "Cancel a running timer. Give the label it was set with; with no " +
                        "label, cancels whichever timer is due to ring next. The phone's " +
                        "timers cannot be listed, so a label that matches nothing cancels " +
                        "nothing.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "label",
                                JSONObject().put("type", "string")
                                    .put("description", "The label the timer was set with."),
                            ),
                        ),
                ),
        ) { args ->
            blocked(Preflight.clock(context)) ?: run {
                val label = args.optString("label").ifBlank { null }
                when {
                    !Clock.dismissTimer(context, label) -> clockRefused()
                    label == null -> "Cancelled the timer that was due to ring next." to false
                    else -> "Cancelled the timer labelled \"$label\"." to false
                }
            }
        }

        val getLocation = ToolSpec(
            name = "get_location",
            featureKey = null,
            definition = JSONObject()
                .put("name", "get_location")
                .put(
                    "description",
                    "Where the user's phone is right now: coordinates, how accurate " +
                        "the fix is, and a street address when one can be resolved. Use " +
                        "for anything that depends on where they are — nearby places, " +
                        "local weather, travel times, which timezone they are in.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "max_age_seconds",
                                JSONObject().put("type", "integer")
                                    .put(
                                        "description",
                                        "Accept a cached fix this old before waiting for a " +
                                            "new one. Defaults to 120. Raise it when a rough " +
                                            "area will do, since a fresh fix can take " +
                                            "several seconds and wakes the GPS.",
                                    ),
                            ),
                        ),
                ),
        ) { args ->
            blocked(Preflight.location(context)) ?: run {
                val maxAge = args.optLong("max_age_seconds", 120).coerceAtLeast(0)
                when (val result = Locator.locate(context, maxAge)) {
                    is Locator.Result.Fix -> {
                        val here = result.location
                        val age = Locator.ageSeconds(here)
                        Log.i(TAG, "get_location -> ${here.provider} ±${here.accuracy}m ${age}s old")
                        buildString {
                            append("%.5f, %.5f".format(here.latitude, here.longitude))
                            result.address?.let { append(" — ").append(it) }
                            append("\nAccurate to about ${here.accuracy.toInt()} m")
                            append(", fix taken ${Clock.humanize(age)} ago")
                            append(" (${here.provider}).")
                            if (age > 600) {
                                append(
                                    "\nThat fix is old — the phone may have moved since. " +
                                        "Call again with max_age_seconds set low if it matters.",
                                )
                            }
                        } to false
                    }
                    // Degraded means the service never got the location foreground
                    // type, and without "all the time" Android then withholds fixes
                    // silently — which looks exactly like being indoors. Say which
                    // it is rather than sending the user to a window for a
                    // permissions problem.
                    Locator.Result.NoFix ->
                        if (McpService.degraded && !Locator.hasBackgroundPermission(context)) {
                            "FAILED: the phone is withholding location from this app. The " +
                                "server started on its own (a reboot or an app update), and " +
                                "location is only allowed while the app is in use. Ask the " +
                                "user to open Phone MCP and set location to \"all the time\" " +
                                "— that fixes it for good. Opening the app also works once." to true
                        } else {
                            "FAILED: location is on and permitted, but no fix came back in " +
                                "time and nothing was cached. This happens indoors. Ask the " +
                                "user to try near a window, or to say where they are." to true
                        }
                }
            }
        }

        val findMyPhone = ToolSpec(
            name = "find_my_phone",
            featureKey = null,
            definition = JSONObject()
                .put("name", "find_my_phone")
                .put(
                    "description",
                    "Make the phone ring loudly so the user can find it. Plays as an " +
                        "alarm, so it sounds even if the phone is on silent or in Do " +
                        "Not Disturb. Stops on its own. Use when the user has lost the " +
                        "phone, not to get their attention for something else.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "seconds",
                                JSONObject().put("type", "integer")
                                    .put(
                                        "description",
                                        "How long to ring for. Defaults to " +
                                            "${Sound.DEFAULT_SECONDS}, at most ${Sound.MAX_SECONDS}.",
                                    ),
                            ).put(
                                "volume",
                                JSONObject().put("type", "integer")
                                    .put(
                                        "description",
                                        "Loudness, 1 to 100. Overrides the phone's alarm " +
                                            "volume for the length of the ring and puts it " +
                                            "back afterwards, so it works even when the " +
                                            "alarm is turned all the way down. Omit to ring " +
                                            "at whatever the user has it set to.",
                                    ),
                            ),
                        ),
                ),
        ) { args ->
            val seconds = args.optLong("seconds", Sound.DEFAULT_SECONDS)
                .coerceIn(1, Sound.MAX_SECONDS)
            val volume = if (args.has("volume")) args.optInt("volume", 100).coerceIn(1, 100) else null
            when (val result = Sound.play(context, seconds, volume)) {
                is Sound.Result.Playing ->
                    "The phone is ringing now for ${Clock.humanize(result.seconds)} at " +
                        "${result.percent}% volume. It stops by itself; call " +
                        "silence_phone to stop it sooner." to false
                Sound.Result.Silent ->
                    "FAILED: the phone's alarm volume is turned all the way down, so " +
                        "ringing it would make no sound. Nothing was played. Pass a " +
                        "volume to override it, or try get_location instead." to true
                is Sound.Result.Failed ->
                    "FAILED: could not play a sound (${result.reason})." to true
            }
        }

        val silencePhone = ToolSpec(
            name = "silence_phone",
            featureKey = null,
            definition = JSONObject()
                .put("name", "silence_phone")
                .put(
                    "description",
                    "Stop the find_my_phone ringing early, once the user says they have " +
                        "found it.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            if (Sound.stop()) {
                "Stopped the ringing." to false
            } else {
                "The phone was not ringing." to false
            }
        }

        val playPodcast = ToolSpec(
            name = "play_podcast",
            featureKey = "pocket_casts",
            definition = JSONObject()
                .put("name", "play_podcast")
                .put(
                    "description",
                    "Resume podcast playback in Pocket Casts on the phone, picking up " +
                        "the episode the user was last listening to. Works whether or " +
                        "not the phone is locked, and whether or not Pocket Casts is " +
                        "already running. Cannot choose a specific show or episode.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            blocked(Preflight.podcastPlayback(context)) ?: when (Podcast.play(context)) {
                Podcast.Result.AlreadyPlaying ->
                    "It was already playing — nothing to do." to false
                Podcast.Result.NotInstalled ->
                    "FAILED: Pocket Casts is not installed on the phone." to true
                else -> "Told Pocket Casts to play." to false
            }
        }

        val nowPlaying = ToolSpec(
            name = "podcast_now_playing",
            featureKey = "pocket_casts",
            definition = JSONObject()
                .put("name", "podcast_now_playing")
                .put(
                    "description",
                    "What Pocket Casts on the phone is playing or paused on: podcast, " +
                        "episode title, position, and whether audio is running. Useful " +
                        "before answering questions about \"this episode\" — the title " +
                        "is enough to find its transcript or show notes on the web.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            blocked(Preflight.podcastState(context)) ?: when (val r = Podcast.nowPlaying(context)) {
                // Idle is a normal answer to "what's playing?", not a failure —
                // reporting it as an error makes callers apologise for the phone.
                Podcast.Result.NoSession ->
                    "Nothing is playing — Pocket Casts is idle. Call play_podcast " +
                        "to start it." to false
                is Podcast.Result.NowPlaying -> {
                    val position = if (r.positionMs >= 0 && r.durationMs > 0) {
                        " at ${Clock.humanize(r.positionMs / 1000)} of " +
                            Clock.humanize(r.durationMs / 1000)
                    } else ""
                    val state = if (r.playing) "Playing" else "Paused"
                    "$state: \"${r.episode ?: "unknown episode"}\" from " +
                        "\"${r.podcast ?: "unknown podcast"}\"$position." to false
                }
                else -> "FAILED: Pocket Casts did not give a usable answer." to true
            }
        }

        val startVoice = ToolSpec(
            name = "start_voice_session",
            featureKey = "voice_gateway",
            definition = JSONObject()
                .put("name", "start_voice_session")
                .put(
                    "description",
                    "Open a live voice session with one of the user's Telegram " +
                        "conversations, on their phone: their unread messages are read " +
                        "aloud, and anything they say is transcribed and sent back. The " +
                        "session runs until stopped.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "conversation",
                                JSONObject().put("type", "string")
                                    .put(
                                        "description",
                                        "Who to talk to: a conversation name, or enough of " +
                                            "one to be unambiguous. voice_status lists them. " +
                                            "Omit it to resume the most recent conversation.",
                                    ),
                            ).put(
                                "catch_up",
                                JSONObject().put("type", "boolean")
                                    .put(
                                        "description",
                                        "Only when the user explicitly asks to hear messages " +
                                            "again: true rewinds to their own last sent message " +
                                            "and replays everything after it, even if already " +
                                            "read or heard. Works on a running session too — no " +
                                            "need to stop it first. Otherwise omit it.",
                                    ),
                            ),
                        ),
                ),
        ) { args ->
            // Absent means "resume the most recent conversation" — the gateway
            // reads a missing extra that way, so the blank is passed through as
            // null rather than refused.
            val conversation = args.optString("conversation").trim().ifBlank { null }
            val catchUp = args.optBoolean("catch_up", false)
            blocked(Preflight.voice(context))
                ?: conversation?.let { blocked(Preflight.voiceConversation(context, it)) }
                ?: when (val r = Voice.start(context, conversation, catchUp)) {
                    is Voice.Result.Accepted ->
                        "The voice session with ${r.conversation ?: "the most recent " +
                            "conversation"} is open on the phone" +
                            (if (catchUp) ", replaying from their last sent message" else "") +
                            "." to false
                    else -> voiceFailure(r)
                }
        }

        val stopVoice = ToolSpec(
            name = "stop_voice_session",
            featureKey = "voice_gateway",
            definition = JSONObject()
                .put("name", "stop_voice_session")
                .put(
                    "description",
                    "End the live voice session, releasing the phone's microphone. " +
                        "Messages already being transcribed still get sent.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            when (val r = Voice.stop(context)) {
                is Voice.Result.Accepted ->
                    "The phone accepted the request to end the session with " +
                        "${r.conversation ?: "the current conversation"}." to false
                else -> voiceFailure(r)
            }
        }

        val voiceStatus = ToolSpec(
            name = "voice_status",
            featureKey = "voice_gateway",
            definition = JSONObject()
                .put("name", "voice_status")
                .put(
                    "description",
                    "Whether a Telegram voice session is running on the phone and who " +
                        "with, plus every conversation available to start one with and " +
                        "how many unread messages each has.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            when (val r = Voice.status(context)) {
                is Voice.Result.Status -> describe(r) to false
                else -> voiceFailure(r)
            }
        }

        val startRecording = ToolSpec(
            name = "start_recording",
            featureKey = "voice_recorder",
            definition = JSONObject()
                .put("name", "start_recording")
                .put(
                    "description",
                    "Record audio on the user's phone, saving it to their notes. Picks " +
                        "up the car's microphone when they are driving. Recording runs " +
                        "until stopped.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            blocked(Preflight.recorderStart(context))
                ?: when (Recorder.start(context)) {
                    Recorder.Result.Accepted -> "The phone is recording." to false
                    Recorder.Result.NotAvailable -> RECORDER_UNAVAILABLE to true
                }
        }

        val stopRecording = ToolSpec(
            name = "stop_recording",
            featureKey = "voice_recorder",
            definition = JSONObject()
                .put("name", "stop_recording")
                .put(
                    "description",
                    "Stop recording audio on the phone and save the file.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            blocked(Preflight.recorderStop(context))
                ?: when (Recorder.stop(context)) {
                    Recorder.Result.Accepted -> "The recording is saved." to false
                    Recorder.Result.NotAvailable -> RECORDER_UNAVAILABLE to true
                }
        }

        val navigate = ToolSpec(
            name = "navigate",
            featureKey = "maps",
            definition = JSONObject()
                .put("name", "navigate")
                .put(
                    "description",
                    "Start turn-by-turn navigation on the user's phone, in Google Maps. " +
                        "The destination can be a place name, an address, \"lat,long\", or " +
                        "the user's saved \"home\" or \"work\". If the phone is locked it " +
                        "shows the unlock prompt, and navigation starts once the user unlocks.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject()
                            .put("destination", JSONObject().put("type", "string").put("description", "Place name, address, lat,long, or home/work."))
                            .put("mode", JSONObject().put("type", "string").put("description", "drive (default), walk, or bike.")),
                    ).put("required", org.json.JSONArray().put("destination")),
                ),
        ) { args ->
            blocked(Preflight.navigation(context)) ?: run {
                val destination = args.optString("destination").trim()
                if (destination.isEmpty()) {
                    "FAILED: give a destination — a place name, address, or the user's saved home or work." to true
                } else {
                    val text = Navigation.navigate(context, destination, args.optString("mode").ifBlank { null })
                    text to text.startsWith("FAILED:")
                }
            }
        }

        return listOf(
            setTimer, cancelTimer, getLocation, findMyPhone, silencePhone,
            playPodcast, nowPlaying,
            startVoice, stopVoice, voiceStatus,
            startRecording, stopRecording,
            navigate,
        )
    }

    /**
     * The recorder is installed — preflight has just said so — but would not
     * take the request. Nothing the user can do beyond opening it once.
     */
    private const val RECORDER_UNAVAILABLE =
        "FAILED: the voice recorder app did not take the request. Ask the user to " +
            "open it once on the phone."

    /**
     * The clock app refusing an intent that preflight said it handles. Rare
     * enough to have one wording for both timer tools.
     */
    private fun clockRefused(): Pair<String, Boolean> =
        "FAILED: the phone's clock app would not take the request. Nothing was " +
            "changed. Ask the user to check their timers by hand." to true

    /**
     * ", due at 15:30" when the caller asked for a clock time, empty otherwise.
     *
     * Echoing the resolved time is the only way an am/pm guess that went the
     * wrong way becomes visible before the timer rings. It reports the time
     * that was asked for rather than one worked back from the interval, so a
     * clamp cannot make it disagree with the request.
     */
    internal fun dueAt(clockTime: TimeOfDay.Resolved?): String =
        if (clockTime == null) "" else ", due at " + TimeOfDay.format(clockTime.time)

    private fun describe(status: Voice.Result.Status): String {
        // The id decides whether a session exists; the name is only how we
        // refer to it, and can be missing for a session that is very much live.
        val name = status.activeConversation ?: status.activeConversationId
        val session = when {
            status.activeConversationId == null -> "No voice session is running."
            status.paused -> "Paused mid-session with $name."
            status.listening && status.micSecured -> "Listening to $name — the microphone is live."
            // Everything else is a session that exists but cannot hear the
            // user: a start still in flight, one Android refused, or a
            // microphone taken away mid-session. Which of those it is changes
            // nothing the caller can do about it — the recording state is in
            // the log for a human. Kept as one outcome on purpose.
            else -> "A session with $name is not listening yet. Ask the user to check the phone."
        }
        val audio = if (status.queueDepth > 0) {
            " ${status.queueDepth} message(s) still to be read aloud."
        } else {
            ""
        }
        val conversations = if (status.conversations.isEmpty()) {
            "No conversations are configured in the app."
        } else {
            "Conversations:\n" + status.conversations.joinToString("\n") {
                val unread = if (it.unread > 0) " — ${it.unread} unread" else ""
                "  ${it.name}$unread${if (it.active) " (active)" else ""}"
            }
        }
        return "$session$audio\n\n$conversations"
    }

    private fun voiceFailure(result: Voice.Result): Pair<String, Boolean> = when (result) {
        is Voice.Result.Refused -> "FAILED: ${result.message}" to true
        Voice.Result.NotAvailable ->
            "FAILED: the voice gateway app did not answer. Ask the user to open it " +
                "once on the phone." to true
        // Not reachable — callers handle every success shape first — but it
        // must not hand the agent a Kotlin toString it can do nothing with.
        else -> "FAILED: the voice gateway did not give a usable answer." to true
    }
}
