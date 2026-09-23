package com.foldforge.studio.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.foldforge.studio.core.apk.ApkSigningKey
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * Per-device signing key for APKs built on the phone. The RSA private key is generated inside the
 * Android Keystore and never leaves it; apksig signs through the Keystore provider.
 * This is a local development key — it is NOT a Google Play upload key.
 */
object ApkKeyProvider {
    private const val ALIAS = "foldforge_apk_signing_v1"
    private const val STORE = "AndroidKeyStore"

    fun getOrCreate(): ApkSigningKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, STORE)
            gen.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=FOLD FORGE Local Build Key, O=FOLD FORGE"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(start.time)
                    .setCertificateNotAfter(end.time)
                    .build(),
            )
            gen.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return ApkSigningKey("FOLDFORGE", key, listOf(cert))
    }

    fun certificateSha256(): String? = runCatching {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        val cert = ks.getCertificate(ALIAS) ?: return null
        java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    fun reset() {
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}
