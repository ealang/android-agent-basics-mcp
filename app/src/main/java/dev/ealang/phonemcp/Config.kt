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

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() {
            prefs.getString(KEY_TOKEN, null)?.let { return it }
            val fresh = generateToken()
            prefs.edit().putString(KEY_TOKEN, fresh).apply()
            return fresh
        }
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Whether the user wants the server up — also what the boot receiver checks. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun regenerateToken(): String = generateToken().also { token = it }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 8910
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"
    }
}
