package dev.ealang.phonemcp

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The note destination: a transcript filed as Markdown, ported from the
 * Python server's `notes.py`, which is the spec — same frontmatter, same
 * filenames, same never-clobber semantics.
 *
 * Writes with plain `File` I/O under a root the caller supplies (in the app,
 * the external-storage root — the recorder's storage code is the precedent,
 * including why MediaStore can't address an arbitrary top-level folder). A refusal
 * here is Permanent: with `mkdirs()` done first, what's left is All-files
 * access missing or the volume gone, both of which need a person.
 */
class NoteWriter(
    private val root: File,
    private val notesDir: String = WebhookConfig.NOTES_DIR,
    private val tags: List<String> = WebhookConfig.NOTE_TAGS,
    /** Hook for the app to run MediaScanner on the result; no-op in tests. */
    private val onWritten: (File) -> Unit = {},
) {

    /** File one note. [whenMs] is the recording time, epoch millis. */
    fun write(text: String, whenMs: Long): StepResult {
        val dir = File(root, notesDir)
        try {
            dir.mkdirs()
            if (!dir.isDirectory) {
                return StepResult.Permanent(
                    "Cannot create the notes folder. Check that Phone MCP has " +
                        "All files access in Settings.",
                )
            }
            val body = render(text, whenMs)
            val path = create(dir, whenMs)
            FileOutputStream(path).use {
                it.write(body.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            onWritten(path)
            return StepResult.Success
        } catch (e: Exception) {
            // Local disk I/O does not fail transiently; whatever this is, a
            // silent retry loop will not fix it.
            return StepResult.Permanent(
                "Could not write the note. Check that Phone MCP has All files " +
                    "access in Settings.",
            )
        }
    }

    /** The Python server's frontmatter, verbatim. */
    internal fun render(text: String, whenMs: Long): String {
        val when_ = Date(whenMs)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(when_)
        val tagList = tags.joinToString(", ") { yamlScalar(it) }
        return "---\n" +
            "tags: [$tagList]\n" +
            "date: \"$date\"\n" +
            "time: \"${clock(whenMs)}\"\n" +
            "---\n" +
            "${text.trim()}\n"
    }

    /**
     * '2026-08-12 14 30.md', ' (2)' for the second that minute — claimed
     * with createNewFile so two writers can never be handed the same name.
     * 24-hour in the filename (no meridiem to disambiguate 02:30 from
     * 14:30); am/pm only in the frontmatter, where it reads well.
     */
    @Throws(IOException::class)
    private fun create(dir: File, whenMs: Long): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH mm", Locale.US).format(Date(whenMs))
        for (counter in 1..MAX_PER_MINUTE) {
            val name = if (counter == 1) "$stamp.md" else "$stamp ($counter).md"
            val file = File(dir, name)
            if (file.createNewFile()) return file
        }
        throw IOException("$MAX_PER_MINUTE notes already exist for this minute")
    }

    /** '02:30 pm', built by hand — %p is locale-dependent, this isn't. */
    private fun clock(whenMs: Long): String {
        val cal = Calendar.getInstance().apply { time = Date(whenMs) }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val hour = hour24 % 12
        val display = if (hour == 0) 12 else hour
        val meridiem = if (hour24 < 12) "am" else "pm"
        return "%02d:%02d %s".format(Locale.US, display, cal.get(Calendar.MINUTE), meridiem)
    }

    private fun yamlScalar(value: String): String =
        if (PLAIN_SCALAR.matches(value)) value
        else "\"${value.replace("\"", "\\\"")}\""

    companion object {
        private val PLAIN_SCALAR = Regex("[A-Za-z0-9_][A-Za-z0-9_./-]*")
        private const val MAX_PER_MINUTE = 1000
    }
}
