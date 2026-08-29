package dev.ealang.phonemcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeOfDayTest {

    /** A zone with a DST change, so the rules that depend on one can be exercised. */
    private val london = ZoneId.of("Europe/London")

    private fun at(text: String): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.parse(text), london)

    private fun until(clock: String, from: String): Long? =
        TimeOfDay.secondsUntil(clock, at(from))

    // ------------------------------- parsing -------------------------------

    @Test
    fun `24 hour times are taken literally`() {
        assertEquals(15 to 30, TimeOfDay.parse("15:30")!!.let { it.time.hour to it.time.minute })
        assertEquals(0 to 5, TimeOfDay.parse("00:05")!!.let { it.time.hour to it.time.minute })
        assertEquals(23 to 59, TimeOfDay.parse("23:59")!!.let { it.time.hour to it.time.minute })
    }

    @Test
    fun `am and pm are honoured however they are written`() {
        for (text in listOf("3pm", "3 PM", "3 p.m.", "3:00pm")) {
            assertEquals(text, 15, TimeOfDay.parse(text)!!.time.hour)
        }
        assertEquals(3, TimeOfDay.parse("3am")!!.time.hour)
    }

    @Test
    fun `noon and midnight are the awkward twelves`() {
        assertEquals(12, TimeOfDay.parse("12pm")!!.time.hour)
        assertEquals(0, TimeOfDay.parse("12am")!!.time.hour)
        assertEquals(12, TimeOfDay.parse("noon")!!.time.hour)
        assertEquals(0, TimeOfDay.parse("midnight")!!.time.hour)
    }

    @Test
    fun `only a bare single-digit hour is ambiguous`() {
        assertFalse(TimeOfDay.parse("3pm")!!.ambiguous)
        assertFalse(TimeOfDay.parse("noon")!!.ambiguous)
        // Only a 24-hour reading fits, so there is nothing to settle.
        assertFalse(TimeOfDay.parse("15:30")!!.ambiguous)
        // Zero-padding is 24-hour notation, so it is not an open question.
        assertFalse(TimeOfDay.parse("09:00")!!.ambiguous)
        assertTrue(TimeOfDay.parse("7")!!.ambiguous)
        assertTrue(TimeOfDay.parse("7:30")!!.ambiguous)
    }

    @Test
    fun `nonsense is rejected rather than coerced`() {
        assertNull(TimeOfDay.parse("tomorrow"))
        assertNull(TimeOfDay.parse(""))
        assertNull(TimeOfDay.parse("25:00"))
        assertNull(TimeOfDay.parse("3:75"))
        assertNull(TimeOfDay.parse("13pm"))
        assertNull(TimeOfDay.parse("0pm"))
        // Seconds are deliberately not accepted.
        assertNull(TimeOfDay.parse("15:30:15"))
    }

    // ------------------------------ resolution -----------------------------

    @Test
    fun `a time later today is today`() {
        assertEquals(90L * 60, until("15:30", "2026-01-15T14:00:00"))
    }

    @Test
    fun `a time already past is tomorrow`() {
        assertEquals(23L * 3600, until("09:00", "2026-01-15T10:00:00"))
    }

    @Test
    fun `the current time exactly means a full day away`() {
        assertEquals(24L * 3600, until("10:00", "2026-01-15T10:00:00"))
    }

    @Test
    fun `an ambiguous hour resolves to whichever comes sooner`() {
        // Morning: 7am is next.
        assertEquals(3600L, until("7", "2026-01-15T06:00:00"))
        // Evening: 7am has gone, so 7pm is next.
        assertEquals(3600L, until("7", "2026-01-15T18:00:00"))
        // Late evening: both have gone today, so tomorrow morning.
        assertEquals(8L * 3600, until("7", "2026-01-15T23:00:00"))
    }

    @Test
    fun `an explicit half of the day is not second-guessed`() {
        // 7am has passed, and pm was not asked for, so this is tomorrow.
        assertEquals(21L * 3600, until("7am", "2026-01-15T10:00:00"))
    }

    @Test
    fun `seconds on the clock now are counted, not rounded away`() {
        // 30 seconds into the minute, so 30 seconds less than a clean hour.
        assertEquals(3570L, until("15:00", "2026-01-15T14:00:30"))
    }

    @Test
    fun `a clock time just ahead gives a very small number`() {
        // The case that has to be clamped by the caller rather than rejected.
        assertEquals(2L, until("15:01", "2026-01-15T15:00:58"))
    }

    @Test
    fun `spring forward keeps the hour the user asked for`() {
        // London skips an hour overnight on 2026-03-29, so tomorrow's 09:00 is
        // 22 hours off rather than the 23 the wall clock suggests.
        assertEquals(22L * 3600, until("09:00", "2026-03-28T10:00:00"))
    }

    @Test
    fun `autumn back keeps the hour the user asked for`() {
        // 2026-10-25 repeats an hour, so the same span stretches to 24.
        assertEquals(24L * 3600, until("09:00", "2026-10-24T10:00:00"))
    }

    @Test
    fun `unparseable text yields nothing to act on`() {
        assertNull(until("half past three", "2026-01-15T10:00:00"))
    }
}
