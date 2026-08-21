package dev.ealang.phonemcp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What the server itself has been through: binds, bind failures, accept
 * failures, deaths, rebinds.
 *
 * The request log answers "what reached the server"; this answers "what was
 * the server doing when nothing did". The battery incident lived entirely in
 * that second category — hours of accept() failures with not one request —
 * and was invisible. In-memory, like the request log, and for the same reason.
 */
object EventLog {

    private const val CAPACITY = 50

    private val acceptFailures = AtomicLong(0)
    private val events = ArrayDeque<String>()

    fun record(summary: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(events) {
            events.addLast("$stamp  $summary")
            if (events.size > CAPACITY) events.removeFirst()
        }
    }

    /** Counted every time; only worth a log line when the streak starts. */
    fun countAcceptFailure(): Long = acceptFailures.incrementAndGet()

    fun acceptFailureCount(): Long = acceptFailures.get()

    /** Newest first. */
    fun tail(limit: Int): List<String> = synchronized(events) {
        events.asReversed().take(limit)
    }
}
