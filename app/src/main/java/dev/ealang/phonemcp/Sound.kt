package dev.ealang.phonemcp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Makes the phone audible so the user can find it.
 *
 * Plays with `USAGE_ALARM`, which is the whole trick: alarms are exempt from
 * silent mode and from Do Not Disturb, so a phone face-down in a sofa still
 * answers. It also means this is loud by definition and must stop on its own —
 * every play is bounded by a deadline.
 *
 * The tone is synthesised rather than loaded from a file, so there is no media
 * permission to grant, no file to go missing, and nothing to fall back to.
 */
object Sound {

    private const val TAG = "PhoneMcpSound"
    const val MAX_SECONDS = 300L
    const val DEFAULT_SECONDS = 30L

    /** 48 kHz divides evenly by 2 kHz, so a cycle is a whole 24 samples. */
    private const val SAMPLE_RATE = 48_000
    private const val FREQUENCY = 2_000.0

    /** A second of tone then a second of silence: pulses are easier to locate. */
    private const val TONE_SAMPLES = SAMPLE_RATE
    private const val SILENCE_SAMPLES = SAMPLE_RATE

    /** Long enough to kill the click at each edge, short enough to stay abrupt. */
    private const val RAMP_SAMPLES = SAMPLE_RATE / 200

    private val main = Handler(Looper.getMainLooper())

    private var track: AudioTrack? = null
    private var stopAt: Runnable? = null
    /** The user's own alarm volume, and the manager to put it back with. */
    private var restoreVolume: Int? = null
    private var mixer: AudioManager? = null

    sealed interface Result {
        data class Playing(val seconds: Long, val percent: Int) : Result
        data object Silent : Result
        data class Failed(val reason: String) : Result
    }

    /** True while a ring is in progress, so the caller can say so. */
    @Synchronized
    fun isPlaying(): Boolean = track != null

    /**
     * @param percent 1–100 of the maximum alarm volume, or null to ring at
     *   whatever the user has the alarm slider set to.
     */
    @Synchronized
    fun play(context: Context, seconds: Long, percent: Int?): Result {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
            ?: return Result.Failed("no audio service")

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)

        // Alarms ignore silent mode but not a zeroed alarm slider. With no
        // volume asked for, saying so beats ringing inaudibly and reporting
        // success.
        if (percent == null && current == 0) return Result.Silent

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
        if (playedAt == 0) return Result.Silent

        return try {
            val samples = pulse()
            val fresh = build(samples)
            fresh.play()
            track = fresh

            val deadline = Runnable { stop() }
            stopAt = deadline
            main.postDelayed(deadline, seconds * 1000)

            val asPercent = playedAt * 100 / max
            Log.i(TAG, "Ringing for ${seconds}s at $asPercent% alarm volume")
            Result.Playing(seconds, asPercent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the tone", e)
            track?.release()
            track = null
            restore()
            Result.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    @Synchronized
    fun stop(): Boolean {
        stopAt?.let { main.removeCallbacks(it) }
        stopAt = null

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
     * One pulse of the loop, as 16-bit mono PCM. The tone is ramped in and out
     * because a sine cut off mid-amplitude is a step change, which is a click.
     */
    private fun pulse(): ShortArray {
        val samples = ShortArray(TONE_SAMPLES + SILENCE_SAMPLES)
        val step = 2 * PI * FREQUENCY / SAMPLE_RATE
        for (i in 0 until TONE_SAMPLES) {
            val envelope = min(i, TONE_SAMPLES - 1 - i).toDouble() / RAMP_SAMPLES
            samples[i] = (sin(i * step) * envelope.coerceAtMost(1.0) * Short.MAX_VALUE).toInt()
                .toShort()
        }
        return samples
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
     * Raising the alarm stream is refused while Do Not Disturb is on unless the
     * app holds `ACCESS_NOTIFICATION_POLICY`, which is a heavier grant than
     * find-my-phone is worth. Ring at the user's own volume instead.
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
