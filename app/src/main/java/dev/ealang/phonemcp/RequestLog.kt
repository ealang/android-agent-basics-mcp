package dev.ealang.phonemcp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What the server has been asked, kept in memory.
 *
 * Exists because "it hasn't processed a single request" was an assumption the
 * app could not check — health probes and client retries were invisible. The
 * total answers "is anything hitting this at all"; the recent lines answer
 * "what, exactly". Lost on process death by design: this is a diagnostic
 * window, not an audit trail.
 */
object RequestLog {

    private const val CAPACITY = 50

    private val total = AtomicLong(0)
    private val recent = ArrayDeque<String>()

    fun record(remote: String?, summary: String) {
        total.incrementAndGet()
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(recent) {
            recent.addLast("$stamp  ${remote ?: "?"}  $summary")
            if (recent.size > CAPACITY) recent.removeFirst()
        }
    }

    fun count(): Long = total.get()

    /** Newest first. */
    fun tail(limit: Int): List<String> = synchronized(recent) {
        recent.asReversed().take(limit)
    }
}
