package com.thrive.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small injectable boundary so auth can be tested without an Android keystore. */
interface SecureValueStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

/** Versioned AES-GCM envelope. The key name is authenticated as AAD. */
object AesGcmEnvelope {
    private const val VERSION = "v1"

    fun encrypt(secretKey: SecretKey, keyName: String, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(keyName.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            VERSION,
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(ciphertext),
        ).joinToString(":")
    }

    fun decrypt(secretKey: SecretKey, keyName: String, envelope: String): String {
        val parts = envelope.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == VERSION) { "Unsupported secure value format" }
        val iv = Base64.getDecoder().decode(parts[1])
        require(iv.size == 12) { "Invalid AES-GCM nonce" }
        val ciphertext = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        cipher.updateAAD(keyName.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}

/**
 * Stores only ciphertext in SharedPreferences. The non-exportable AES key is
 * created and retained by Android Keystore, so copied preferences cannot be
 * decrypted on a different device.
 */
class AndroidKeystoreSecureValueStore(
    private val settings: SettingsStore,
) : SecureValueStore {

    override fun put(key: String, value: String) {
        val encoded = AesGcmEnvelope.encrypt(secretKey(), key, value)
        settings.putStringImmediate(storageKey(key), encoded)
    }

    override fun get(key: String): String? {
        val encoded = settings.getString(storageKey(key), null) ?: return null
        return runCatching { AesGcmEnvelope.decrypt(secretKey(), key, encoded) }
            .getOrElse {
                // A restored preference file cannot be decrypted with another
                // device's hardware-bound key. Fail closed and discard it.
                settings.remove(storageKey(key))
                null
            }
    }

    override fun remove(key: String) = settings.remove(storageKey(key))

    private fun storageKey(key: String) = "secure_v1_$key"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "thrive.account.storage.v1"
    }
}
