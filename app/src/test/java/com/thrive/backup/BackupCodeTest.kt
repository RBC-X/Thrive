package com.thrive.backup

import com.thrive.app.data.remote.BackupCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodeTest {

    @Test
    fun generatedCodesAreValidLengthAndAlphabet() {
        repeat(200) {
            val code = BackupCode.generate()
            assertEquals(BackupCode.LENGTH, code.length)
            assertTrue("all chars from safe alphabet", code.all { it in BackupCode.ALPHABET })
            assertTrue(BackupCode.isValid(code))
        }
    }

    @Test
    fun codesDifferAcrossGenerations() {
        val first = BackupCode.generate()
        val second = BackupCode.generate()
        assertTrue("two codes should differ", first != second)
    }

    @Test
    fun isValidRejectsBadInput() {
        assertFalse(BackupCode.isValid(""))
        assertFalse(BackupCode.isValid("abc")) // too short
        assertFalse(BackupCode.isValid("abcdefghijklmnop")) // too long
        assertFalse(BackupCode.isValid("ABCDEFGH")) // uppercase
        assertFalse(BackupCode.isValid("abc def!")) // symbols/spaces
        assertFalse(BackupCode.isValid("abc-defgh")) // hyphen
    }

    @Test
    fun isValidAcceptsServerShape() {
        assertTrue(BackupCode.isValid("ab12cd34"))
        assertTrue(BackupCode.isValid("k7f3m2xq"))
        assertTrue(BackupCode.isValid("abcdefghijkl")) // 12 chars, max
    }
}
