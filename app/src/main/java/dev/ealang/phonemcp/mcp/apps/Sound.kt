package dev.ealang.phonemcp.mcp.apps

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ealang.phonemcp.R
import dev.ealang.phonemcp.mcp.generic.Notifier
import kotlin.math.min

/**
 * Makes the phone audible so the user can find it.
 *
 * Plays with `USAGE_ALARM`, which is the whole trick: alarms are exempt from
 * silent mode and from Do Not Disturb, so a phone face-down in a sofa still
 * answers. It also means this is loud by definition and must stop on its own —
 * every play is bounded by a deadline.
 *
 * The sound is synthesised rather than loaded from a file, so there is no
 * media permission to grant, no file to go missing, and nothing to fall back
 * to. The voice is a 25%-duty pulse wave — the chiptune lead — and a play is
 * up to two tracks of notes mixed together, looping until the deadline. With
 * no tracks given, a built-in two-voice phrase plays.
 */
object Sound {

    private const val TAG = "PhoneMcpSound"
    const val MAX_SECONDS = 300L
    const val DEFAULT_SECONDS = 30L
    const val MAX_TRACKS = 2

    /** The loop is held whole in a static AudioTrack buffer, so it is bounded. */
    const val MAX_LOOP_SECONDS = 30.0

    private const val SAMPLE_RATE = 48_000

    /** Long enough to kill the click at each edge, short enough to stay abrupt. */
    private const val RAMP_SAMPLES = SAMPLE_RATE / 200

    /** A note of the loop; a pitch of zero (or less) is a rest. */
    data class Note(val pitchHz: Double, val seconds: Double)

    /**
     * The default ring: a Cmaj7 arpeggio under a slower melody, ending in a
     * rest so the loop breathes. Picked by ear from synthesised candidates
     * (2026-10-02).
     */
    private val DEFAULT_TRACKS: List<List<Note>> = run {
        val e = 0.125
        val s = 0.0625
        val q = 0.25
        val melody = listOf(
            Note(988.0, q), Note(0.0, e), Note(784.0, q), Note(0.0, e),
            Note(659.0, q), Note(784.0, e), Note(988.0, q), Note(0.0, q),
            Note(0.0, 0.5),
        )
        val bass = buildList {
            repeat(4) {
                add(Note(262.0, s)); add(Note(330.0, s))
                add(Note(392.0, s)); add(Note(494.0, s))
            }
            add(Note(0.0, q))
        }
        listOf(melody, bass)
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * The stop notification's id. Notifier owns 2000 for agent posts; this is
     * the app's own, so it cannot collide with an agent's tag.
     */
    private const val STOP_ID = 2001

    private var track: AudioTrack? = null
    private var stopAt: Runnable? = null
    /** The user's own alarm volume, and the manager to put it back with. */
    private var restoreVolume: Int? = null
    private var mixer: AudioManager? = null
    /** Where the stop notification was posted, so stop() can take it down. */
    private var shade: NotificationManager? = null

    /** Loop length in seconds, for bounding a caller's tracks before playing. */
    fun loopSeconds(tracks: List<List<Note>>): Double =
        tracks.maxOf { track -> track.sumOf { it.seconds } }

    /**
     * @param percent 1–100 of the maximum alarm volume, or null to ring at
     *   whatever the user has the alarm slider set to.
     * @param tracks up to [MAX_TRACKS] note tracks mixed together; the longest
     *   one sets the loop length. Null plays the built-in ring. The caller
     *   bounds the loop with [loopSeconds] before getting here.
     * @return whether playback started. Details like the effective volume go
     *   to logcat, not to the caller.
     */
    @Synchronized
    fun play(context: Context, seconds: Long, percent: Int?, tracks: List<List<Note>>? = null): Boolean {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
            ?: return false

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)

        stop()

        val playedAt = if (percent == null) {
            current
        } else {
            // Ceil, so 1% is still audible rather than rounding to silence.
            val target = ((percent * max + 99) / 100).coerceIn(1, max)
            if (setVolume(audio, target)) {
                restoreVolume = current
                mixer = audio
                target
            } else {
                current
            }
        }

        return try {
            val samples = render(tracks ?: DEFAULT_TRACKS)
            val fresh = build(samples)
            fresh.play()
            track = fresh

            val deadline = Runnable { stop() }
            stopAt = deadline
            main.postDelayed(deadline, seconds * 1000)

            offerStop(context.applicationContext)

            Log.i(TAG, "Ringing for ${seconds}s at ${playedAt * 100 / max}% alarm volume")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the tone", e)
            track?.release()
            track = null
            restore()
            false
        }
    }

    @Synchronized
    fun stop(): Boolean {
        stopAt?.let { main.removeCallbacks(it) }
        stopAt = null

        shade?.cancel(STOP_ID)
        shade = null

        restore()

        val current = track ?: return false
        track = null
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            // Never got out of the stopped state; releasing is all that is left.
        }
        current.release()
        return true
    }

    /**
     * A stop button in the shade for the person holding the phone. On the
     * silent channel, because they can already hear the ring — the
     * notification only has to be findable, not announce itself. stop() takes
     * it down on every exit path (the stop tool, the deadline, the service
     * dying, the button itself), so it lives exactly as long as the sound.
     */
    private fun offerStop(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val stop = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, StopRingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, Notifier.infoChannel(context))
            .setContentTitle("Phone is ringing")
            .setContentText("Tap to stop the sound.")
            .setSmallIcon(R.drawable.ic_stat_claw)
            .setContentIntent(stop)
            // Not swipeable: the only way it leaves is by the ring ending, so
            // it cannot be dismissed while the noise carries on.
            .setOngoing(true)
            .build()
        manager.notify(STOP_ID, notification)
        shade = manager
    }

    /**
     * One loop of the mix, as 16-bit mono PCM. The longest track sets the
     * length; a shorter one is padded with silence. Tracks are weighted
     * lead-heavy so a melody over an arpeggio balances the way the default
     * does.
     */
    private fun render(tracks: List<List<Note>>): ShortArray {
        val rendered = tracks.map { renderTrack(it) }
        val length = rendered.maxOf { it.size }
        val weights = if (rendered.size == 1) listOf(1.0) else listOf(0.6, 0.4)

        val samples = ShortArray(length)
        for (i in 0 until length) {
            var mixed = 0.0
            rendered.forEachIndexed { t, track ->
                if (i < track.size) mixed += track[i] * weights[t]
            }
            samples[i] = (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    /**
     * One track of notes as a 25%-duty pulse wave, zero-mean so the speaker
     * sees no DC. Each note is ramped in and out, because a waveform cut off
     * mid-amplitude is a step change, which is a click.
     */
    private fun renderTrack(notes: List<Note>): DoubleArray {
        val counts = notes.map { (it.seconds * SAMPLE_RATE).toInt() }
        val track = DoubleArray(counts.sum())
        var at = 0
        notes.forEachIndexed { n, note ->
            val count = counts[n]
            if (note.pitchHz > 0) {
                val ramp = min(RAMP_SAMPLES, count / 4).coerceAtLeast(1)
                for (i in 0 until count) {
                    val phase = (i * note.pitchHz / SAMPLE_RATE) % 1.0
                    val wave = if (phase < 0.25) 1.0 else -1.0 / 3
                    val envelope = (min(i, count - 1 - i).toDouble() / ramp).coerceAtMost(1.0)
                    track[at + i] = wave * envelope
                }
            }
            at += count
        }
        return track
    }

    /**
     * A static track, so the whole pulse sits in the buffer and the hardware
     * loops it. A streaming track would need a thread kept alive to feed it.
     */
    private fun build(samples: ShortArray): AudioTrack {
        val bytes = samples.size * Short.SIZE_BYTES
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()

        track.write(samples, 0, samples.size)
        track.setLoopPoints(0, samples.size, -1)
        return track
    }

    /**
     * Sets the alarm stream's volume, or says it could not. On a refusal the
     * caller falls back to ringing at the user's own volume.
     */
    private fun setVolume(audio: AudioManager, index: Int): Boolean = try {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to change the alarm volume", e)
        false
    }

    private fun restore() {
        val previous = restoreVolume ?: return
        val audio = mixer
        restoreVolume = null
        mixer = null
        if (audio != null) setVolume(audio, previous)
    }
}

/**
 * What the stop notification fires. Not exported, so only this app's own
 * pending intent can reach it.
 */
class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sound.stop()
    }
}
