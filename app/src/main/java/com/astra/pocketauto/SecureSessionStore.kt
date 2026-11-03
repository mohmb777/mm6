package com.astra.pocketauto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.content.Context
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores a user-supplied session identifier encrypted with an Android Keystore AES-GCM key.
 * This is local storage only; it does not authenticate to or send requests to Pocket Option.
 */
class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("astra_secure_session", Context.MODE_PRIVATE)
    private val keyAlias = "astra_pocket_session_key"

    fun save(value: String) {
        require(value.isNotBlank()) { "Session ID is empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun hasSavedValue(): Boolean =
        !prefs.getString("data", null).isNullOrBlank() &&
            !prefs.getString("iv", null).isNullOrBlank()

    fun read(): String? {
        val data = prefs.getString("data", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun clear() {
        prefs.edit().clear().apply()
        // Keep the Keystore key; a future save can reuse it.
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
