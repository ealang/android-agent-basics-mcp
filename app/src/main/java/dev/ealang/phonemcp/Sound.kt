package dev.ealang.phonemcp

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Makes the phone audible so the user can find it.
 *
 * Plays with `USAGE_ALARM`, which is the whole trick: alarms are exempt from
 * silent mode and from Do Not Disturb, so a phone face-down in a sofa still
 * answers. It also means this is loud by definition and must stop on its own —
 * every play is bounded by a deadline.
 */
object Sound {

    private const val TAG = "PhoneMcpSound"
    const val MAX_SECONDS = 300L
    const val DEFAULT_SECONDS = 30L

    /** The ring tone. Missing files fall back to the system alarm. */
    private const val DEFAULT_FILE = "/storage/emulated/0/Alarms/alarm.mp3"

    val PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var stopAt: Runnable? = null
    /** The user's own alarm volume, and the manager to put it back with. */
    private var restoreVolume: Int? = null
    private var mixer: AudioManager? = null

    sealed interface Result {
        data class Playing(val seconds: Long, val source: String, val percent: Int) : Result
        data object Silent : Result
        data class Failed(val reason: String) : Result
    }

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** True while a ring is in progress, so the caller can say so. */
    @Synchronized
    fun isPlaying(): Boolean = player != null

    /**
     * @param percent 0–100 of the maximum alarm volume, or null to ring at
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

        val (uri, source) = resolve(context)
            ?: return Result.Failed("no tone file and no system alarm sound")

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
            val fresh = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            player = fresh

            val deadline = Runnable { stop() }
            stopAt = deadline
            main.postDelayed(deadline, seconds * 1000)

            val asPercent = playedAt * 100 / max
            Log.i(TAG, "Ringing for ${seconds}s from $source at $asPercent% alarm volume")
            Result.Playing(seconds, source, asPercent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play $uri", e)
            player?.release()
            player = null
            restore()
            Result.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    @Synchronized
    fun stop(): Boolean {
        stopAt?.let { main.removeCallbacks(it) }
        stopAt = null

        restore()

        val current = player ?: return false
        player = null
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            // Already finished; releasing is all that is left to do.
        }
        current.release()
        return true
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

    /**
     * The configured file if it is there, else the same file located through
     * MediaStore (the direct path fails if the volume is mounted elsewhere),
     * else the system alarm sound. Find-my-phone should not fail because a
     * file moved.
     */
    private fun resolve(context: Context): Pair<Uri, String>? {
        val file = File(DEFAULT_FILE)
        if (file.canRead()) return Uri.fromFile(file) to file.name

        indexed(context, file.name)?.let { return it to "${file.name} (via MediaStore)" }

        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        return fallback?.let { it to "the phone's default alarm sound" }
    }

    private fun indexed(context: Context, name: String): Uri? = try {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore lookup failed for $name", e)
        null
    }
}
