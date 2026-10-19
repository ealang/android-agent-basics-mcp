package dev.ealang.phonemcp.mcp.apps

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.mcp.Preflight
import dev.ealang.phonemcp.mcp.ToolSpec
import dev.ealang.phonemcp.mcp.blocked
import org.json.JSONArray
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

        val findMyPhonePlay = ToolSpec(
            name = "find_my_phone_play",
            featureKey = "find_my_phone",
            definition = JSONObject()
                .put("name", "find_my_phone_play")
                .put(
                    "description",
                    "Play a sound on the phone to help the user find it; it sounds " +
                        "even on silent or Do Not Disturb. A default sound plays " +
                        "unless tracks are given.",
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
                            ).put(
                                "tracks",
                                JSONObject().put("type", "array")
                                    .put(
                                        "description",
                                        "A melody to play instead of the default sound: up " +
                                            "to ${Sound.MAX_TRACKS} tracks sounding together, " +
                                            "each an array of notes. The longest track sets " +
                                            "the loop, which repeats until the time is up.",
                                    )
                                    .put("maxItems", Sound.MAX_TRACKS)
                                    .put(
                                        "items",
                                        JSONObject().put("type", "array").put(
                                            "items",
                                            JSONObject().put("type", "object").put(
                                                "properties",
                                                JSONObject().put(
                                                    "pitch",
                                                    JSONObject().put("type", "number")
                                                        .put(
                                                            "description",
                                                            "Hz; 0 or omitted is a rest.",
                                                        ),
                                                ).put(
                                                    "seconds",
                                                    JSONObject().put("type", "number")
                                                        .put("description", "Note length."),
                                                ),
                                            ).put("required", JSONArray().put("seconds")),
                                        ),
                                    ),
                            ),
                        ),
                ),
        ) { args ->
            val seconds = args.optLong("seconds", Sound.DEFAULT_SECONDS)
                .coerceIn(1, Sound.MAX_SECONDS)
            val volume = if (args.has("volume")) args.optInt("volume", 100).coerceIn(1, 100) else null
            val tracks = if (args.has("tracks")) parseTracks(args.optJSONArray("tracks")) else null
            when {
                tracks is TracksParse.Bad -> "FAILED: ${tracks.why}" to true
                else -> {
                    val notes = (tracks as? TracksParse.Ok)?.tracks
                    if (Sound.play(context, seconds, volume, notes)) {
                        "" to false
                    } else {
                        "FAILED: the phone could not play a sound." to true
                    }
                }
            }
        }

        val findMyPhoneStop = ToolSpec(
            name = "find_my_phone_stop",
            featureKey = "find_my_phone",
            definition = JSONObject()
                .put("name", "find_my_phone_stop")
                .put(
                    "description",
                    "Stop the find_my_phone_play sound early.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            // Fire-and-forget: stopping a ring that already ended is the
            // common case, not a condition worth telling the caller about.
            Sound.stop()
            "" to false
        }

        val getVolume = ToolSpec(
            name = "get_volume",
            featureKey = "volume",
            definition = JSONObject()
                .put("name", "get_volume")
                .put(
                    "description",
                    "The current level of each of the phone's volume sliders, " +
                        "as percentages.",
                )
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", JSONObject()),
                ),
        ) { _ ->
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio == null) {
                "FAILED: the phone could not read its volume." to true
            } else {
                STREAMS.entries.joinToString("\n") { (name, stream) ->
                    val max = audio.getStreamMaxVolume(stream)
                    "$name: ${audio.getStreamVolume(stream) * 100 / max}%"
                } to false
            }
        }

        val setVolume = ToolSpec(
            name = "set_volume",
            featureKey = "volume",
            definition = JSONObject()
                .put("name", "set_volume")
                .put(
                    "description",
                    "Set or adjust one of the phone's volume sliders.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "stream",
                                JSONObject().put("type", "string")
                                    .put("enum", JSONArray(STREAMS.keys.toList()))
                                    .put("description", "Which slider to set."),
                            ).put(
                                "percent",
                                JSONObject().put("type", "integer")
                                    .put("description", "Absolute level, 0 to 100; 0 is silent."),
                            ).put(
                                "change",
                                JSONObject().put("type", "integer")
                                    .put(
                                        "description",
                                        "Relative adjustment, -100 to 100; stops at 1 " +
                                            "rather than muting. Give percent or change, " +
                                            "not both.",
                                    ),
                            ),
                        )
                        .put("required", JSONArray().put("stream")),
                ),
        ) { args ->
            val stream = STREAMS[args.optString("stream")]
            val percent = args.optInt("percent", -1)
            val audio = context.getSystemService(AudioManager::class.java)
            when {
                stream == null ->
                    "FAILED: stream must be one of ${STREAMS.keys.joinToString(", ")}." to true
                args.has("percent") == args.has("change") ->
                    "FAILED: give exactly one of percent or change." to true
                args.has("percent") && percent !in 0..100 ->
                    "FAILED: percent must be 0 to 100." to true
                audio == null -> "FAILED: the phone could not change its volume." to true
                else -> {
                    val max = audio.getStreamMaxVolume(stream)
                    val goal = if (args.has("percent")) percent else {
                        val current = audio.getStreamVolume(stream) * 100 / max
                        // A relative change never mutes — "a bit quieter" must
                        // not leave the phone silent, so decreases stop at 1.
                        // Except from an already-silent slider, where staying
                        // at 0 beats a decrease that raises the volume.
                        (current + args.optInt("change"))
                            .coerceIn(if (current == 0) 0 else 1, 100)
                    }
                    // Ceil, so 1% is still a step up from silent rather than
                    // rounding back down to it.
                    val target = if (goal == 0) 0 else ((goal * max + 99) / 100).coerceIn(1, max)
                    try {
                        audio.setStreamVolume(stream, target, 0)
                        "" to false
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Not allowed to set the $stream stream", e)
                        "FAILED: the phone refused to change that volume; Do Not Disturb is in the way." to true
                    }
                }
            }
        }

        val setFlashlight = ToolSpec(
            name = "set_flashlight",
            featureKey = "flashlight",
            definition = JSONObject()
                .put("name", "set_flashlight")
                .put(
                    "description",
                    "Turn the phone's flashlight on or off.",
                )
                .put(
                    "inputSchema",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "on",
                                JSONObject().put("type", "boolean")
                                    .put("description", "true for on, false for off."),
                            ),
                        )
                        .put("required", JSONArray().put("on")),
                ),
        ) { args ->
            when {
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) ->
                    "FAILED: this phone has no flashlight." to true
                setTorch(context, args.optBoolean("on", true)) -> "" to false
                else -> "FAILED: the phone could not switch its flashlight." to true
            }
        }

        return listOf(
            getLocation, findMyPhonePlay, findMyPhoneStop,
            getVolume, setVolume, setFlashlight,
        )
    }

    /** The sliders in the phone's volume panel, by the names users use. */
    private val STREAMS = linkedMapOf(
        "media" to AudioManager.STREAM_MUSIC,
        "call" to AudioManager.STREAM_VOICE_CALL,
        "ring" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM,
    )

    /**
     * Flips the torch on the first camera that has one. No permission is
     * needed for the torch alone — only for capturing images.
     */
    private fun setTorch(context: Context, on: Boolean): Boolean = try {
        val cameras = context.getSystemService(CameraManager::class.java)!!
        val flash = cameras.cameraIdList.first {
            cameras.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cameras.setTorchMode(flash, on)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not switch the torch", e)
        false
    }

    private sealed interface TracksParse {
        data class Ok(val tracks: List<List<Sound.Note>>) : TracksParse
        data class Bad(val why: String) : TracksParse
    }

    /**
     * Light validation at the boundary: the shape has to be right and the
     * loop has to fit the playback buffer; pitches and lengths are otherwise
     * taken as given.
     */
    private fun parseTracks(given: JSONArray?): TracksParse {
        if (given == null || given.length() == 0 || given.length() > Sound.MAX_TRACKS) {
            return TracksParse.Bad(
                "tracks must be up to ${Sound.MAX_TRACKS} arrays of {pitch, seconds} notes.",
            )
        }
        val tracks = (0 until given.length()).map { t ->
            val notes = given.optJSONArray(t)
                ?: return TracksParse.Bad("track ${t + 1} is not an array of notes.")
            if (notes.length() == 0) {
                return TracksParse.Bad("track ${t + 1} is empty.")
            }
            (0 until notes.length()).map { n ->
                val note = notes.optJSONObject(n)
                    ?: return TracksParse.Bad("track ${t + 1}, note ${n + 1} is not an object.")
                val length = note.optDouble("seconds", Double.NaN)
                if (length.isNaN() || length <= 0) {
                    return TracksParse.Bad(
                        "track ${t + 1}, note ${n + 1} needs seconds greater than 0.",
                    )
                }
                Sound.Note(note.optDouble("pitch", 0.0), length)
            }
        }
        if (Sound.loopSeconds(tracks) > Sound.MAX_LOOP_SECONDS) {
            return TracksParse.Bad(
                "the loop is longer than ${Sound.MAX_LOOP_SECONDS.toInt()} seconds.",
            )
        }
        return TracksParse.Ok(tracks)
    }
}
