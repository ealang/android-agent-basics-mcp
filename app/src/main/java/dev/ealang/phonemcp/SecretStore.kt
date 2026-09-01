package dev.ealang.phonemcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest: AES-GCM under a key that lives in the Android Keystore.
 *
 * The point is not to defend against another app — app-private prefs already
 * do that, by uid — but against the data leaving the phone. `allowBackup` is
 * on, so plain prefs are swept into cloud backups and device-to-device
 * copies. A Keystore key cannot be exported or backed up (it sits in the
 * TEE; the OS itself only gets to *use* it), so what a backup carries is
 * ciphertext whose key is stranded in this phone's hardware.
 *
 * This is the same construction Jetpack's EncryptedSharedPreferences used;
 * that wrapper was deprecated in 2024 with the advice to do exactly this
 * directly.
 *
 * If the Keystore key is ever invalidated (a factory reset restore, some
 * OEM security events), decryption fails and the secret reads as absent —
 * the bearer token regenerates and the OpenAI key must be re-pasted. That
 * is the designed failure: inconvenient, never insecure.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("phone-mcp-secrets", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // The key no longer opens this blob. Stale ciphertext would fail
            // this way forever, so clear it and report the secret as unset.
            Log.w(TAG, "Could not decrypt '$name'; treating it as unset", e)
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** The wrapping key, generated inside the Keystore on first use. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-auth requirement: these secrets are read by a
                // service on a locked phone; a key gated on unlock would
                // take the server down whenever the screen is off.
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "phone-mcp"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "phone-mcp-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BITS = 96
        private const val IV_BYTES = IV_BITS / 8
        private const val TAG_BITS = 128
    }
}
