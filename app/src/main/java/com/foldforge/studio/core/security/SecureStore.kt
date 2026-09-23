package com.foldforge.studio.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores secrets (AI API key, GitHub token) encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (non-exportable). Only ciphertext + IV are written to SharedPreferences.
 * Values are never logged.
 */
class SecureStore(context: Context, private val keystoreAvailable: Boolean = true) {
    private val prefs = context.getSharedPreferences("foldforge_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        if (!keystoreAvailable) throw IllegalStateException("Android Keystore unavailable; refusing to store secret in plain text")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, encoded).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        if (!keystoreAvailable) return null
        return try {
            val (iv, ct) = stored.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null // key invalidated (e.g. device restore) — treat as not set
        }
    }

    fun has(name: String) = prefs.contains(name)

    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        const val AI_API_KEY = "ai_api_key"
        const val GITHUB_TOKEN = "github_token"
        const val IMAGE_API_KEY = "image_api_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "foldforge_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
