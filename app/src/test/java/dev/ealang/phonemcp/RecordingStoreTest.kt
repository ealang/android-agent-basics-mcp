package dev.ealang.phonemcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

class RecordingStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun meta(receivedAt: Long = 1_700_000_000_000L) = Meta(
        trigger = Trigger.DOUBLE_CLICK_HOLD,
        recordedAtMs = receivedAt - 5_000,
        client = "ring",
        pebbleTranscript = "hello",
        receivedAtMs = receivedAt,
    )

    @Test
    fun `enqueue then pending round-trips the meta`() {
        val store = RecordingStore(temp.root)
        store.enqueue(null, meta())

        val items = store.pending()
        assertEquals(1, items.size)
        assertEquals(Trigger.DOUBLE_CLICK_HOLD, items[0].meta.trigger)
        assertEquals("hello", items[0].meta.pebbleTranscript)
        assertEquals("ring", items[0].meta.client)
    }

    @Test
    fun `pending is FIFO even across a restart`() {
        var store = RecordingStore(temp.root)
        store.enqueue(null, meta(1_700_000_000_000L))
        store.enqueue(null, meta(1_700_000_001_000L))

        // A new instance over the same root: the sequence re-seeds, so the
        // third item must still sort after the first two.
        store = RecordingStore(temp.root)
        store.enqueue(null, meta(1_700_000_002_000L))

        val names = store.pending().map { it.name }
        assertEquals(3, names.size)
        assertEquals(names.sorted(), names)
        assertEquals(1_700_000_002_000L, store.pending().last().meta.receivedAtMs)
    }

    @Test
    fun `audio file is moved into the item`() {
        val store = RecordingStore(temp.root)
        val upload = temp.newFile("upload.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val item = store.enqueue(upload, meta())

        assertNotNull(item.audio())
        assertEquals(3, item.audio()!!.length())
    }

    @Test
    fun `leftovers in tmp are swept on startup`() {
        File(temp.root, "webhook-queue/tmp/half-written").mkdirs()

        val store = RecordingStore(temp.root)

        assertEquals(0, store.pending().size)
        assertEquals(
            emptyList<String>(),
            File(temp.root, "webhook-queue/tmp").listFiles()!!.map { it.name },
        )
    }

    @Test
    fun `complete removes the item`() {
        val store = RecordingStore(temp.root)
        val item = store.enqueue(null, meta())

        store.complete(item)

        assertEquals(0, store.pending().size)
    }

    @Test
    fun `fail parks the item with its reason, retry re-queues it last`() {
        val store = RecordingStore(temp.root)
        val first = store.enqueue(null, meta(1_700_000_000_000L))
        store.fail(first, "no such folder")
        store.enqueue(null, meta(1_700_000_001_000L))

        assertEquals(1, store.pending().size)
        val failed = store.failed()
        assertEquals(1, failed.size)
        assertEquals("no such folder", failed[0].failureReason())

        store.retry(failed[0])

        assertEquals(0, store.failed().size)
        val names = store.pending().map { it.name }
        assertEquals(2, names.size)
        // The retried item sorts last, so it cannot block newer traffic.
        assertEquals("hello", store.pending().last().meta.pebbleTranscript)
        assertEquals(names.sorted(), names)
        assertNull(store.pending().last().failureReason())
    }

    @Test
    fun `discard removes a failed item for good`() {
        val store = RecordingStore(temp.root)
        val item = store.enqueue(null, meta())
        store.fail(item, "bad")

        store.discard(store.failed()[0])

        assertEquals(0, store.failed().size)
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `processor state is absent until written, then round-trips`() {
        val store = RecordingStore(temp.root)
        val item = store.enqueue(null, meta())

        assertNull(item.processorState("telegram"))

        store.writeProcessorState(item, "telegram", JSONObject().put("message_id", 42))

        val state = store.pending()[0].processorState("telegram")
        assertNotNull(state)
        assertEquals(42, state!!.getInt("message_id"))
    }

    @Test
    fun `transcript marker makes the step idempotent`() {
        val store = RecordingStore(temp.root)
        val item = store.enqueue(null, meta())

        assertNull(item.transcript())
        store.writeTranscript(item, "typed on the phone")
        assertEquals("typed on the phone", store.pending()[0].transcript())
    }

    @Test
    fun `two enqueues in the same millisecond get distinct names`() {
        val store = RecordingStore(temp.root)
        val a = store.enqueue(null, meta(1_700_000_000_000L))
        val b = store.enqueue(null, meta(1_700_000_000_000L))
        assertTrue(a.name != b.name)
    }
}
