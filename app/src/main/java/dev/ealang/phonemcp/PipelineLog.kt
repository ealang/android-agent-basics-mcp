package dev.ealang.phonemcp

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pipeline's history: every webhook receipt and every step outcome, on
 * disk. Unlike [RequestLog] and [EventLog] — in-memory diagnostic windows —
 * this one is a record: "did that recording from the car make it?" is a
 * question asked hours later, possibly after a restart.
 *
 * JSONL, appended from whichever thread is doing the work; appends are
 * serialized on the instance. Rotation keeps one previous generation.
 */
class PipelineLog(root: File) {

    private val file = File(root, "webhook-log.jsonl")

    fun record(item: String?, event: String, detail: String? = null) {
        val line = JSONObject()
            .put("ts", STAMP.get()!!.format(Date()))
            .put("item", item ?: JSONObject.NULL)
            .put("event", event)
            .put("detail", detail ?: JSONObject.NULL)
            .toString()
        synchronized(this) {
            try {
                if (file.length() > MAX_BYTES) {
                    file.renameTo(File(file.parentFile, file.name + ".1"))
                }
                file.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not append to the pipeline log", e)
            }
        }
    }

    /** The most recent [limit] lines, newest first, rendered for the UI. */
    fun tail(limit: Int): List<String> = synchronized(this) {
        try {
            if (!file.exists()) return emptyList()
            file.readLines().takeLast(limit).asReversed().map { line ->
                runCatching {
                    val json = JSONObject(line)
                    val item = json.optString("item").ifBlank { null }
                    val detail = json.optString("detail").ifBlank { null }
                    buildString {
                        append(json.optString("ts"))
                        append("  ")
                        append(json.optString("event"))
                        item?.let { append("  $it") }
                        detail?.let { append(" — $it") }
                    }
                }.getOrDefault(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the pipeline log", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "phone-mcp"
        private const val MAX_BYTES = 256L * 1024

        private val STAMP = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }
    }
}
