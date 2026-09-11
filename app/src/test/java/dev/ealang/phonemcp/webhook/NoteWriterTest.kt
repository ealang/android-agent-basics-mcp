package dev.ealang.phonemcp.webhook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class NoteWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var defaultTz: TimeZone

    @Before
    fun pinTimeZone() {
        // The writer renders in the phone's local zone; pin it so the
        // expected strings below are the same on any build machine.
        defaultTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(defaultTz)
    }

    // 2023-11-14 22:13:20 UTC
    private val evening = 1_700_000_000_000L

    // 2023-11-15 02:30:00 UTC
    private val morning = 1_700_015_400_000L

    @Test
    fun `note matches the Python server's template`() {
        val writer = NoteWriter(temp.root, notesDir = "notes")

        val result = writer.write("  buy milk  ", evening)

        assertEquals(StepResult.Success, result)
        val note = File(temp.root, "notes/2023-11-14 22 13.md")
        assertTrue(note.exists())
        assertEquals(
            "---\n" +
                "tags: [autotag, index]\n" +
                "date: \"2023-11-14\"\n" +
                "time: \"10:13 pm\"\n" +
                "---\n" +
                "buy milk\n",
            note.readText(),
        )
    }

    @Test
    fun `morning times render as am with a leading zero`() {
        val writer = NoteWriter(temp.root, notesDir = "notes")
        assertTrue(writer.render("x", morning).contains("time: \"02:30 am\""))
    }

    @Test
    fun `noon and midnight render as 12`() {
        val writer = NoteWriter(temp.root, notesDir = "notes")
        val midnightUtc = 1_700_006_400_000L // 2023-11-15 00:00:00 UTC
        val noonUtc = 1_700_049_600_000L // 2023-11-15 12:00:00 UTC
        assertTrue(writer.render("x", midnightUtc).contains("time: \"12:00 am\""))
        assertTrue(writer.render("x", noonUtc).contains("time: \"12:00 pm\""))
    }

    @Test
    fun `a second note in the same minute gets a counter, never a clobber`() {
        val writer = NoteWriter(temp.root, notesDir = "notes")

        writer.write("first", evening)
        writer.write("second", evening)
        writer.write("third", evening)

        val dir = File(temp.root, "notes")
        assertEquals(
            listOf(
                "2023-11-14 22 13 (2).md",
                "2023-11-14 22 13 (3).md",
                "2023-11-14 22 13.md",
            ),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
        assertTrue(File(dir, "2023-11-14 22 13.md").readText().endsWith("first\n"))
        assertTrue(File(dir, "2023-11-14 22 13 (2).md").readText().endsWith("second\n"))
    }

    @Test
    fun `an unwritable root reports Permanent, not Transient`() {
        // A file where the directory should be: mkdirs fails, isDirectory is
        // false — the "no All files access" shape.
        temp.newFile("notes")
        val writer = NoteWriter(temp.root, notesDir = "notes")

        val result = writer.write("text", evening)

        assertTrue(result is StepResult.Permanent)
        assertTrue((result as StepResult.Permanent).why.contains("All files access"))
    }

    @Test
    fun `unsafe tags are quoted in the frontmatter`() {
        val writer = NoteWriter(temp.root, notesDir = "notes", tags = listOf("ok-tag", "has space"))
        assertTrue(writer.render("x", evening).contains("tags: [ok-tag, \"has space\"]"))
    }
}
