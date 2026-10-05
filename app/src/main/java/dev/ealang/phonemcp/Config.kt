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
     *
     * Open question (2026-09-04, deliberately unresolved): a blank field
     * binds every interface, which a user without networking knowledge can
     * do by accident. Auto-detecting tunnel interfaces or offering a
     * dropdown of addresses might fix that; whether tunnels can be detected
     * reliably is the unanswered part.
     */
    var bindAddress: String
        get() = prefs.getString(KEY_BIND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BIND, value.trim()).apply()

    /** The bearer token every /mcp request must carry. */
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

    /**
     * Per-tool feature switches, enforced per request — a tool whose key is
     * off vanishes from tools/list and is refused at dispatch.
     *
     * Keys are namespaced with "feature_" so they can be enumerated later.
     */
    fun featureEnabled(key: String, default: Boolean = true): Boolean =
        prefs.getBoolean("feature_$key", default)

    fun setFeatureEnabled(key: String, value: Boolean) =
        prefs.edit().putBoolean("feature_$key", value).apply()

    /**
     * Whether a runtime permission's system dialog has ever been shown,
     * one flag per permission group. Needed because the platform cannot
     * distinguish never-asked from don't-ask-again — rationale is false
     * for both — and the two lead to different screens. Only dialog
     * permissions (notifications, location) have a flag; Settings-only
     * grants (overlay, media, battery) have no dialog to track.
     */
    fun permissionAsked(key: String): Boolean = prefs.getBoolean("asked_$key", false)

    fun setPermissionAsked(key: String) =
        prefs.edit().putBoolean("asked_$key", true).apply()

    /**
     * Whether the user wants the server up: the state behind the UI's one
     * switch, set when the service starts and cleared when it stops. Also
     * what the boot receiver checks, so a server left on comes back after a
     * reboot or an app update and one left off stays off.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Why the last attempt to bring the service up failed, or null.
     *
     * Persisted in its own right because the failures worth catching here
     * are the ones that take the process with them — the reason has to
     * outlive the crash so [MainActivity] can replay it into the logs, and
     * there is no adb on this phone.
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
    }
}
