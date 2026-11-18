package io.github.ealang.agentbasicsmcp.mcp.pebble

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What watchapps have sent this app, held until a caller reads it.
 *
 * One queue per watchapp, in arrival order: the messages it sent, and the
 * moments CoreApp reported it opened and closed. A read takes everything
 * queued for that app and leaves the queue empty, so each event is handed
 * out once.
 *
 * Memory only, on purpose. This is for talking with a watchapp while it is
 * in use, where an event from before the process last died is stale rather
 * than valuable. For the same reason events past a caller-chosen age are
 * thrown away at the read instead of returned.
 */
object WatchInbox {

    /** Per watchapp. Past it the oldest event makes room and is counted. */
    const val CAPACITY = 1024

    enum class Kind(val wire: String) {
        MESSAGE("message"),
        OPENED("app_opened"),
        CLOSED("app_closed"),
    }

    private class Event(
        val kind: Kind,
        /** Wall clock, for the caller. */
        val receivedAt: OffsetDateTime,
        /** Monotonic, for ageing: the wall clock can be set. */
        val elapsedMs: Long,
        val tuples: JSONArray?,
    )

    private class Queue {
        val events = ArrayDeque<Event>()
        /** Lost to [CAPACITY] since the last read. */
        var dropped = 0
    }

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val queues = HashMap<String, Queue>()

    /** [tuples] only for a message. */
    fun put(uuid: String, kind: Kind, tuples: JSONArray? = null) {
        val event = Event(kind, OffsetDateTime.now(), SystemClock.elapsedRealtime(), tuples)
        lock.withLock {
            val queue = queues.getOrPut(uuid.lowercase()) { Queue() }
            if (queue.events.size >= CAPACITY) {
                queue.events.removeFirst()
                queue.dropped++
            }
            queue.events.addLast(event)
            arrived.signalAll()
        }
    }

    /**
     * Everything queued for [uuid] no older than [maxAgeMs], removed from the
     * queue, with the count dropped for space since the last read. With
     * nothing to return, waits up to [waitMs] for the first event to arrive
     * and returns as soon as one does.
     *
     * Events older than [maxAgeMs] are discarded here and not counted as
     * dropped: the caller asked not to see them.
     *
     * Taking an event is the only copy of it leaving, so a caller that has
     * been kept waiting is checked with [stillThere] first — when an event
     * wakes it, and every [CHECK_MS] besides — and one that has gone gets
     * nothing: the events stay for whoever asks next, and the thread it
     * was holding is let go. A caller answered without waiting is not
     * checked; it asked a moment ago.
     */
    fun take(uuid: String, maxAgeMs: Long, waitMs: Long, stillThere: () -> Boolean): JSONObject {
        val key = uuid.lowercase()
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(waitMs)
            while (true) {
                val queue = queues[key]
                if (queue != null) {
                    val oldest = SystemClock.elapsedRealtime() - maxAgeMs
                    while (queue.events.firstOrNull()?.let { it.elapsedMs < oldest } == true) {
                        queue.events.removeFirst()
                    }
                    if (queue.events.isNotEmpty() || queue.dropped > 0 || remaining <= 0) {
                        queues.remove(key)
                        return render(queue.events, queue.dropped)
                    }
                } else if (remaining <= 0) {
                    return render(emptyList(), 0)
                }
                val slice = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(CHECK_MS))
                remaining -= slice - arrived.awaitNanos(slice)
                if (!stillThere()) return render(emptyList(), 0)
            }
        }
    }

    /** How often a waiting caller is checked for having gone. */
    private const val CHECK_MS = 30_000L

    private fun render(events: Collection<Event>, dropped: Int): JSONObject {
        val out = JSONArray()
        for (event in events) {
            val item = JSONObject()
                .put("type", event.kind.wire)
                .put("received_at", STAMP.format(event.receivedAt.truncatedTo(ChronoUnit.MILLIS)))
            if (event.kind == Kind.MESSAGE) item.put("tuples", event.tuples ?: JSONArray())
            out.put(item)
        }
        return JSONObject().put("events", out).put("dropped", dropped)
    }

    /** Local time with its offset, always to the millisecond. */
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
}
