package dev.ealang.phonemcp

import android.content.Context
import java.security.SecureRandom

/**
 * Settings, in SharedPreferences.
 *
 * The token is generated on first launch rather than left blank: a server whose
 * auth is "" until someone fills it in is a server that ships unauthenticated.
 */
class Config(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("phone-mcp", Context.MODE_PRIVATE)

    private val secrets = SecretStore(context)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /**
     * The address to listen on, or "" for every interface.
     *
     * Set to the phone's tailnet address to keep the server off the LAN. The
     * Tailscale interface is a generic `tunN` on Android, so there is nothing
     * stable to match on by name — the address itself is the durable handle,
     * since Tailscale pins it to the node.
     */
    var bindAddress: String
        get() = prefs.getString(KEY_BIND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BIND, value.trim()).apply()

    var token: String
        get() {
            secrets.get(KEY_TOKEN)?.let { return it }
            // Installed before the SecretStore existed: lift the plaintext
            // token into it, once, so the client keeps working unchanged.
            prefs.getString(KEY_TOKEN, null)?.let {
                secrets.put(KEY_TOKEN, it)
                prefs.edit().remove(KEY_TOKEN).apply()
                return it
            }
            val fresh = generateToken()
            secrets.put(KEY_TOKEN, fresh)
            return fresh
        }
        set(value) = secrets.put(KEY_TOKEN, value)

    /** The OpenAI API key for transcription, or null while unset. */
    var openAiKey: String?
        get() = secrets.get(KEY_OPENAI)
        set(value) = secrets.put(KEY_OPENAI, value?.trim()?.ifBlank { null })

    /** Whether the user wants the server up — also what the boot receiver checks. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Why the last attempt to bring the service up failed, or null.
     *
     * Persisted rather than left to [EventLog] because the failures worth
     * catching here are the ones that take the process with them — an
     * in-memory log is empty by the time anyone can read it, and there is no
     * adb on this phone.
     */
    var lastStartFailure: String?
        get() = prefs.getString(KEY_START_FAILURE, null)
        set(value) = prefs.edit().putString(KEY_START_FAILURE, value).apply()

    fun regenerateToken(): String = generateToken().also { token = it }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 8910
        private const val KEY_PORT = "port"
        private const val KEY_BIND = "bind_address"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START_FAILURE = "last_start_failure"
        private const val KEY_OPENAI = "openai_key"
    }
}
