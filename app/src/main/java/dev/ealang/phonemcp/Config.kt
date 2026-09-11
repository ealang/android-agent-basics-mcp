package dev.ealang.phonemcp

import android.content.Context
import dev.ealang.phonemcp.webhook.PebbleWebhookServer
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
     *
     * Open question (2026-09-04, deliberately unresolved): a blank field
     * binds every interface, which a user without networking knowledge can
     * do by accident. Auto-detecting tunnel interfaces or offering a
     * dropdown of addresses might fix that; whether tunnels can be detected
     * reliably is the unanswered part. The webhook no longer rides on this
     * setting — it has its own loopback-only listener.
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

    /**
     * Telegram application credentials, from my.telegram.org. These identify
     * the *application*, not the user — every install of a distributed
     * Telegram client ships the same pair compiled into the binary, and the
     * official clients' values are public. So: plain prefs, not SecretStore.
     * Editable for now so a dev pair can be pasted in; a distributed build
     * would bake a default in and hide the fields.
     */
    var telegramApiId: Int?
        get() = prefs.getInt(KEY_TG_API_ID, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_TG_API_ID, value ?: 0).apply()

    var telegramApiHash: String?
        get() = prefs.getString(KEY_TG_API_HASH, null)?.ifBlank { null }
        set(value) = prefs.edit().putString(KEY_TG_API_HASH, value?.trim() ?: "").apply()

    /** The destination chat, entered manually as a numeric id (stage-4 scope). */
    var telegramChatId: Long?
        get() = prefs.getLong(KEY_TG_CHAT, 0L).takeIf { it != 0L }
        set(value) = prefs.edit().putLong(KEY_TG_CHAT, value ?: 0L).apply()

    /**
     * TDLib's local-database encryption key, random per install. Unlike the
     * api id this one *is* a secret — the database holds the account's
     * session keys — hence SecretStore, for the same allowBackup reason as
     * the bearer token.
     */
    val telegramDbKey: ByteArray
        get() {
            secrets.get(KEY_TG_DB_KEY)?.let {
                return android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
            }
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            secrets.put(
                KEY_TG_DB_KEY,
                android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP),
            )
            return fresh
        }

    /**
     * Feature switches. The MCP tool keys are enforced per request — a tool
     * whose key is off vanishes from tools/list and is refused at dispatch.
     * The webhook destination switches are still UI-only: every configured
     * destination stays active regardless, until their wiring lands.
     *
     * Keys are namespaced with "feature_" so they can be enumerated later.
     */
    fun featureEnabled(key: String, default: Boolean = true): Boolean =
        prefs.getBoolean("feature_$key", default)

    fun setFeatureEnabled(key: String, value: Boolean) =
        prefs.edit().putBoolean("feature_$key", value).apply()

    /**
     * Which watch button presses a webhook destination should claim:
     * "single", "double" or "both". UI-only, same caveat as [featureEnabled];
     * today the recorder sends everything and the pipeline fans out to
     * whatever is configured.
     */
    fun buttonAssignment(destination: String): String =
        prefs.getString("buttons_$destination", "both") ?: "both"

    fun setButtonAssignment(destination: String, value: String) =
        prefs.edit().putString("buttons_$destination", value).apply()

    /**
     * The webhook listener's port. UI-only for now, same caveat as
     * [featureEnabled]: the listener still binds PebbleWebhookServer.PORT
     * until the wiring step lands.
     */
    var webhookPort: Int
        get() = prefs.getInt(KEY_WEBHOOK_PORT, PebbleWebhookServer.PORT)
        set(value) = prefs.edit().putInt(KEY_WEBHOOK_PORT, value).apply()

    /**
     * Signature line appended to forwarded Telegram messages, on its own
     * line. Read at pickup by TelegramDelivery, so an edit applies to
     * queued items too. Blank appends nothing.
     */
    var telegramSignature: String
        get() = prefs.getString(KEY_TG_SIGNATURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TG_SIGNATURE, value.trim()).apply()

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
        private const val KEY_TG_API_ID = "telegram_api_id"
        private const val KEY_TG_API_HASH = "telegram_api_hash"
        private const val KEY_TG_CHAT = "telegram_chat_id"
        private const val KEY_TG_DB_KEY = "telegram_db_key"
        private const val KEY_TG_SIGNATURE = "telegram_signature"
        private const val KEY_WEBHOOK_PORT = "webhook_port"
    }
}
