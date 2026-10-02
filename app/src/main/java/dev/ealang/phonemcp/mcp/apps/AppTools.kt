package dev.ealang.phonemcp.mcp.apps

import android.content.Context
import android.util.Log
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.mcp.Preflight
import dev.ealang.phonemcp.mcp.ToolSpec
import dev.ealang.phonemcp.mcp.blocked
import org.json.JSONObject

/**
 * The phone-itself tools: capabilities of the phone with no generic route,
 * written for a weak model — full preflight, one code path, `FAILED:` with
 * the fix named. Anything that integrates a third-party app belongs in a
 * skill file over the generic tools instead (CLAUDE.md, generic-first).
 */
object AppTools {

    private const val TAG = "PhoneMcpServer"

    fun specs(context: Context): List<ToolSpec> {
        val getLocation = ToolSpec(
            name = "get_location",
            featureKey = "location",
            definition = JSONObject()
                .put("name", "get_location")
                .put(
                    "description",
                    "Where the user's phone is right now: coordinates, how accurate " +
                        "the fix is, and a street address when one can be resolved.",
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
                                            "new one. Defaults to 120.",
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
                    "Play an alarm on the phone to help the user find it; it sounds " +
                        "even on silent or Do Not Disturb.",
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
                                        "Loudness, 1 to 100. Omit to ring at the user's " +
                                            "current alarm volume.",
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
                    "Stop the find_my_phone alarm early.",
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

        return listOf(getLocation, findMyPhone, silencePhone)
    }

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
}
