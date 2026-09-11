package dev.ealang.phonemcp.webhook

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriberTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a missing key is Transient - recordings wait, none are lost`() {
        val transcriber = OpenAiTranscriber { null }

        val result = transcriber.transcribe(temp.newFile("audio.m4a"))

        assertTrue(result is TranscribeResult.Failed)
        val step = (result as TranscribeResult.Failed).result
        assertTrue(step is StepResult.Transient)
        assertTrue((step as StepResult.Transient).why.contains("key"))
    }
}
