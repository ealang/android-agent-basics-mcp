package dev.ealang.phonemcp.webhook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaTest {

    /**
     * The absent fields are written as explicit JSON nulls, and on the device
     * org.json's optString turns those into the literal string "null" — which
     * is how audio-only recordings once produced notes whose whole body was
     * the word "null". The JVM library doesn't reproduce that, so this test
     * pins the contract, not the bug.
     */
    @Test
    fun `null transcript and client survive the JSON round-trip as nulls`() {
        val meta = Meta(
            trigger = Trigger.DOUBLE_CLICK_HOLD,
            recordedAtMs = 123L,
            client = null,
            pebbleTranscript = null,
            receivedAtMs = 456L,
        )

        val back = Meta.fromJson(JSONObject(meta.toJson().toString()))

        assertNull(back.pebbleTranscript)
        assertNull(back.client)
        assertEquals(meta, back)
    }

    @Test
    fun `present transcript and client round-trip intact`() {
        val meta = Meta(
            trigger = Trigger.SINGLE_CLICK_HOLD,
            recordedAtMs = 123L,
            client = "ring",
            pebbleTranscript = "buy milk",
            receivedAtMs = 456L,
        )

        assertEquals(meta, Meta.fromJson(JSONObject(meta.toJson().toString())))
    }
}
