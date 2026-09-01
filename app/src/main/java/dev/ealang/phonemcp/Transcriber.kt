package dev.ealang.phonemcp

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the transcription step can come back with. Failure is a [StepResult]
 * so the worker applies its ordinary rules: Transient blocks the head and
 * the item is retried whole; Permanent parks it in `failed/` with the audio
 * intact, so a Retry after the problem is fixed replays it.
 */
sealed interface TranscribeResult {
    data class Text(val text: String) : TranscribeResult
    data class Failed(val result: StepResult) : TranscribeResult
}

/**
 * The OpenAI transcription step (stage 2). Blocking by design: it runs on
 * the worker's thread, and the queue is *supposed* to wait behind a
 * recording that is not transcribed yet — ordering is the contract.
 *
 * Error policy, from the design doc: a recording is never lost to a bad
 * moment. Everything that could work later — no network, a 5xx, a rate
 * limit, no key pasted yet — is Transient. Permanent is reserved for what
 * retrying cannot fix: OpenAI affirmatively rejecting this audio or this
 * key.
 */
class OpenAiTranscriber(
    /** Read per call, so pasting the key un-blocks the queue without a restart. */
    private val apiKey: () -> String?,
) : PipelineWorker.Transcriber {

    override fun transcribe(audio: File): TranscribeResult {
        val key = apiKey()
            ?: return TranscribeResult.Failed(
                StepResult.Transient("no OpenAI key — paste one in the app"),
            )

        return try {
            call(audio, key)
        } catch (e: IOException) {
            // The network's fault until proven otherwise.
            Log.i(TAG, "Transcription attempt failed; will retry", e)
            TranscribeResult.Failed(
                StepResult.Transient("OpenAI unreachable (${e::class.java.simpleName})"),
            )
        }
    }

    @Throws(IOException::class)
    private fun call(audio: File, key: String): TranscribeResult {
        val boundary = "phone-mcp-${System.nanoTime()}"
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary",
            )

            connection.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(
                        (
                            "--$boundary\r\n" +
                                "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                                "$value\r\n"
                            ).toByteArray(),
                    )
                }
                field("model", MODEL)
                out.write(
                    (
                        "--$boundary\r\n" +
                            "Content-Disposition: form-data; name=\"file\"; " +
                            "filename=\"${audio.name}\"\r\n" +
                            "Content-Type: audio/mp4\r\n\r\n"
                        ).toByteArray(),
                )
                audio.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val status = connection.responseCode
            if (status in 200..299) {
                val body = connection.inputStream.bufferedReader().readText()
                return TranscribeResult.Text(JSONObject(body).optString("text"))
            }

            val error = connection.errorStream?.bufferedReader()?.readText() ?: ""
            Log.w(TAG, "OpenAI answered $status: ${error.take(500)}")
            return TranscribeResult.Failed(
                when (status) {
                    // The service's problem, or pressure: comes right later.
                    in 500..599, 429, 408 ->
                        StepResult.Transient("OpenAI answered $status")
                    // Bad or expired key. Transient on purpose: every
                    // recording made while the key is wrong must survive to
                    // be transcribed once it is fixed, not be filed as failed.
                    401, 403 ->
                        StepResult.Transient(
                            "OpenAI rejected the key — check it in the app",
                        )
                    // This request, affirmatively refused: retrying the same
                    // bytes cannot end differently.
                    else ->
                        StepResult.Permanent("OpenAI rejected this audio ($status)")
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "phone-mcp"
        private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"

        /** The current small transcription model; cheaper than whisper-1, better WER. */
        private const val MODEL = "gpt-4o-mini-transcribe"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
    }
}
