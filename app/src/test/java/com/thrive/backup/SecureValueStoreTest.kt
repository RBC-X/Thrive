package com.thrive.backup

import com.thrive.app.data.local.AesGcmEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator

class SecureValueStoreTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun aesGcmEnvelopeRoundTripsWithoutPlaintext() {
        val secret = key()
        val plaintext = "refresh-token-that-must-not-be-plain"
        val encrypted = AesGcmEnvelope.encrypt(secret, "google_session", plaintext)

        assertFalse(encrypted.contains(plaintext))
        assertEquals(plaintext, AesGcmEnvelope.decrypt(secret, "google_session", encrypted))
    }

    @Test
    fun aesGcmEnvelopeRejectsWrongAssociatedKeyName() {
        val secret = key()
        val encrypted = AesGcmEnvelope.encrypt(secret, "google_session", "secret")
        assertThrows(Exception::class.java) {
            AesGcmEnvelope.decrypt(secret, "another_setting", encrypted)
        }
    }

    @Test
    fun aesGcmEnvelopeRejectsTampering() {
        val secret = key()
        val encrypted = AesGcmEnvelope.encrypt(secret, "google_session", "secret")
        val tampered = encrypted.dropLast(1) + if (encrypted.last() == 'A') "B" else "A"
        assertThrows(Exception::class.java) {
            AesGcmEnvelope.decrypt(secret, "google_session", tampered)
        }
    }
}
