package dev.ealang.phonemcp

import org.json.JSONObject

/**
 * The shared vocabulary of the webhook pipeline — see
 * `docs/webhook-pipeline.md`, which is the design this implements.
 */

/**
 * What the watch did, frozen at receipt. A fact, never policy: the
 * destination it routes to is looked up in [WebhookConfig] each time the
 * worker picks the item up, so a config change redirects queued items.
 */
enum class Trigger {
    SINGLE_CLICK_HOLD,
    DOUBLE_CLICK_HOLD,

    /**
     * Absent or unrecognised. Routed like a single click — the louder
     * outcome; a message the user didn't want beats a recording filed where
     * they aren't looking. Genuinely occurs in the field: the watch's
     * retries of old recordings arrive without the header.
     */
    UNKNOWN,
    ;

    companion object {
        fun fromHeader(value: String?): Trigger = when (value?.trim()?.lowercase()) {
            "single-click-hold" -> SINGLE_CLICK_HOLD
            "double-click-hold" -> DOUBLE_CLICK_HOLD
            else -> UNKNOWN
        }
    }
}

enum class Destination { NOTE, TELEGRAM }

/**
 * The facts of one webhook receipt — everything `meta.json` holds. No
 * policy: notably no destination (resolved at pickup) and no processing
 * state (that lives in the marker files).
 */
data class Meta(
    val trigger: Trigger,
    /** Epoch milliseconds, as the watch reported it. 0 when absent. */
    val recordedAtMs: Long,
    val client: String?,
    /** Pebble's own transcription, when the watch sent one. */
    val pebbleTranscript: String?,
    /** Epoch milliseconds when we received it. */
    val receivedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("trigger", trigger.name)
        .put("recorded_at_ms", recordedAtMs)
        .put("client", client ?: JSONObject.NULL)
        .put("pebble_transcript", pebbleTranscript ?: JSONObject.NULL)
        .put("received_at_ms", receivedAtMs)

    companion object {
        fun fromJson(json: JSONObject): Meta = Meta(
            trigger = runCatching { Trigger.valueOf(json.getString("trigger")) }
                .getOrDefault(Trigger.UNKNOWN),
            recordedAtMs = json.optLong("recorded_at_ms"),
            client = json.stringOrNull("client"),
            pebbleTranscript = json.stringOrNull("pebble_transcript"),
            receivedAtMs = json.optLong("received_at_ms"),
        )
    }
}

/**
 * What one step did with one item. Steps only report; the worker owns what
 * the answers mean (`failed/`, ordering, stopping on a blocked head).
 */
sealed interface StepResult {
    data object Success : StepResult

    /** Will succeed later — the item stays at the head and blocks the queue. */
    data class Transient(val why: String) : StepResult

    /** Will never succeed — the item moves to `failed/` and the queue moves on. */
    data class Permanent(val why: String) : StepResult
}

/**
 * What became of a handed-off item, answered by the destination's reconcile.
 * Deliberately not [StepResult]: Pending must not block the queue — a
 * handed-off item is out of the ordering contract, waiting only for its
 * confirmation, and the items behind it flow past.
 */
sealed interface ReconcileResult {
    /** Confirmed at the server — the item can finally be deleted. */
    data object Delivered : ReconcileResult

    /** Still in flight, or unanswerable right now. Look again next drain. */
    data object Pending : ReconcileResult

    /** Given up for good — the item moves to `failed/`. */
    data class Failed(val why: String) : ReconcileResult
}

/**
 * What one drain pass did, handed to the orchestrator's `onDrainFinished`.
 * The worker owns no retry timer; whether and when to try again is decided
 * from this report.
 */
data class DrainReport(
    val completed: Int,
    /** The item blocking the head, with the transient reason, or null. */
    val blockedOn: String?,
    /** Items moved to `failed/` during this pass. */
    val newlyFailed: Int,
)

/**
 * Stage 1's hardcoded policy, in one place so stage 3 (settings) is a
 * mechanical swap of constants for preferences.
 */
object WebhookConfig {

    fun destinationFor(trigger: Trigger): Destination = when (trigger) {
        Trigger.DOUBLE_CLICK_HOLD -> Destination.NOTE
        Trigger.SINGLE_CLICK_HOLD, Trigger.UNKNOWN -> Destination.TELEGRAM
    }

    /** Relative to the external-storage root, like the recorder's template. */
    const val NOTES_DIR = "Notes"

    /** Frontmatter tags on every note, matching the Python server's default. */
    val NOTE_TAGS = listOf("autotag", "index")

    /** Reject request bodies past this; the watch sends ~100 KB. */
    const val MAX_BODY_BYTES = 25L * 1024 * 1024
}

/**
 * Read a string field that may be an explicit JSON null. Android's org.json
 * differs from the desktop library here: `optString` on `JSONObject.NULL`
 * returns the *string* "null" on the device (desktop returns ""), which is
 * how audio-only recordings briefly produced notes whose whole body was the
 * word "null" — the round-tripped null transcript read back as real text.
 * `isNull` first is the only read that behaves the same on both.
 */
internal fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).ifBlank { null }
