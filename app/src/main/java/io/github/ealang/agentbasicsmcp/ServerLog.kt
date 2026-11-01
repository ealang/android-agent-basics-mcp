package io.github.ealang.agentbasicsmcp

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The server's log, the complete story in one file: the high-level events at
 * info (a tool called, and on what), the plumbing at debug/warn/error (binds,
 * socket deaths, auth rejections), told apart by level.
 *
 * JSONL on disk — `{ts, level, event, detail?, item?}` — appended from
 * whichever thread is doing the work; appends are serialized on the instance.
 * The line being JSON *is* the structured-logging story: a future field
 * appends without breaking an old reader. Two rotating generations of ~10 KB:
 * deliberately tiny, a diagnostic window that survives a restart, not an
 * archive.
 *
 * Counters ride along in memory, since app start: cheap to bump anywhere,
 * shown as plain text in the UI, gone with the process — the log is the
 * durable record, the counters are the glance.
 */
class ServerLog(root: File, name: String) {

    private val file = File(root, "$name-log.jsonl")

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun bump(counter: String) {
        counters.getOrPut(counter) { AtomicLong() }.incrementAndGet()
    }

    fun count(counter: String): Long = counters[counter]?.get() ?: 0

    fun debug(event: String, detail: String? = null, item: String? = null) =
        record("debug", event, detail, item)

    fun info(event: String, detail: String? = null, item: String? = null) =
        record("info", event, detail, item)

    fun warn(event: String, detail: String? = null, item: String? = null) =
        record("warn", event, detail, item)

    fun error(event: String, detail: String? = null, item: String? = null) =
        record("error", event, detail, item)

    private fun record(level: String, event: String, detail: String?, item: String?) {
        val line = JSONObject()
            .put("ts", STAMP.get()!!.format(Date()))
            .put("level", level)
            .put("event", event)
            .apply {
                detail?.let { put("detail", it) }
                item?.let { put("item", it) }
            }
            .toString()
        synchronized(this) {
            try {
                if (file.length() > MAX_BYTES) {
                    file.renameTo(File(file.parentFile, file.name + ".1"))
                }
                file.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not append to ${file.name}", e)
            }
        }
    }

    /** Both generations gone; the next record starts a fresh file. */
    fun clear() = synchronized(this) {
        file.delete()
        File(file.parentFile, file.name + ".1").delete()
    }

    /**
     * The most recent [limit] lines across both generations, newest first,
     * rendered for the UI. Info reads as plain prose; the other levels are
     * tagged, because "why is it broken" is what a level is for.
     */
    fun tail(limit: Int): List<String> = synchronized(this) {
        try {
            val previous = File(file.parentFile, file.name + ".1")
            val lines = (if (previous.exists()) previous.readLines() else emptyList()) +
                (if (file.exists()) file.readLines() else emptyList())
            lines.takeLast(limit).asReversed().map { line ->
                runCatching {
                    val json = JSONObject(line)
                    val level = json.optString("level", "info")
                    buildString {
                        append(json.optString("ts"))
                        if (level != "info") append("  [$level]")
                        append("  ")
                        append(json.optString("event"))
                        json.optString("item").ifEmpty { null }?.let { append("  $it") }
                        json.optString("detail").ifEmpty { null }?.let { append(" — $it") }
                    }
                }.getOrDefault(line)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${file.name}", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "agent-basics-mcp"

        /** Per generation. Tiny on purpose — see the class comment. */
        private const val MAX_BYTES = 10L * 1024

        private val STAMP = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }
    }
}

/**
 * The one instance, shared by everything that logs: the activity, the
 * service, the server. Initialized by whichever entry point runs first;
 * idempotent, so each just calls [init] on its own filesDir.
 */
object Logs {
    @Volatile
    private var instance: ServerLog? = null

    fun init(root: File) {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    instance = ServerLog(root, "mcp")
                }
            }
        }
    }

    val mcp: ServerLog get() = instance!!
}
