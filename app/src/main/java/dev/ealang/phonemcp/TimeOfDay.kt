package dev.ealang.phonemcp

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Turns a clock time the user named ("3pm", "15:30") into a number of seconds
 * from now.
 *
 * This exists so a small model does not have to do date arithmetic. Asking it
 * to subtract the current time from the target time is exactly the kind of step
 * it gets wrong, and a wrong answer there is a timer that rings at the wrong
 * hour rather than an error anything could notice.
 *
 * Resolution is deliberately forgiving. Anything already past today means
 * tomorrow, and a bare hour with no am/pm means whichever reading comes up
 * sooner, because that is what a person means when they say "wake me at 7".
 * Seconds are not accepted: nobody asks for a timer at 3:00:15, and allowing
 * them would only add a way to get the input wrong.
 */
object TimeOfDay {

    /**
     * Hour, optional minute, optional am/pm — with or without dots or a space,
     * in any case. Accepts 15:30, 3pm, 3 PM, 3:30pm, 15.
     */
    private val CLOCK = Regex(
        """^(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A parsed clock time. [ambiguous] marks one that could be either half of
     * the day, which is then settled against the current time.
     */
    data class Parsed(val time: LocalTime, val ambiguous: Boolean)

    /** The clock time [text] names, or null if it is not a time at all. */
    fun parse(text: String): Parsed? {
        val trimmed = text.trim().lowercase()
        when (trimmed) {
            "noon", "midday" -> return Parsed(LocalTime.NOON, false)
            "midnight" -> return Parsed(LocalTime.MIDNIGHT, false)
        }

        val match = CLOCK.matchEntire(trimmed) ?: return null
        val hourText = match.groupValues[1]
        val hour = hourText.toInt()
        val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (minute > 59) return null
        val meridiem = match.groupValues[3].replace(".", "")

        if (meridiem.isEmpty()) {
            if (hour > 23) return null
            // A zero-padded hour is someone writing 24-hour time, so "09:00"
            // is the morning and only a bare "9" is an open question.
            return Parsed(LocalTime.of(hour, minute), ambiguous = hour <= 12 && hourText.length == 1)
        }

        // With am/pm, 12 is the odd one out: 12am is 00:00 and 12pm is 12:00.
        if (hour < 1 || hour > 12) return null
        val base = if (hour == 12) 0 else hour
        return Parsed(LocalTime.of(if (meridiem == "pm") base + 12 else base, minute), false)
    }

    /**
     * Seconds from [now] until the next occurrence of [text], or null if it is
     * not a time.
     *
     * For an ambiguous hour both readings are considered and the sooner wins,
     * so "at 7" means 7am when asked at 6am and 7pm when asked at 6pm.
     */
    fun secondsUntil(text: String, now: ZonedDateTime): Long? {
        val parsed = parse(text) ?: return null
        val candidates = if (parsed.ambiguous) {
            listOf(parsed.time, parsed.time.plusHours(12))
        } else {
            listOf(parsed.time)
        }
        return candidates.minOf { secondsTo(it, now) }
    }

    /** Seconds from [now] to the next moment the clock reads [target]. */
    private fun secondsTo(target: LocalTime, now: ZonedDateTime): Long {
        // Rolls the date rather than adding a fixed 24 hours, so a DST change
        // in between does not shift the hour it fires at.
        var at = now.with(target)
        if (!at.isAfter(now)) at = now.toLocalDate().plusDays(1).atTime(target).atZone(now.zone)
        return Duration.between(now, at).seconds
    }

    /** How the resolved time reads back to the user, as 24-hour hh:mm. */
    fun format(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)
}
