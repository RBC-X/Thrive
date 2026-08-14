package com.thrive.app.data.remote

import com.thrive.app.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * Free, anonymous backup for saved deals. Each install generates an 8-char
 * code shown in Settings; favorites are pushed to the Thrive sync server under
 * that code, and entering the same code on another phone merges them there —
 * no account, no API key, no email. The code is the only credential.
 *
 * Merge semantics are a union: restoring never deletes what the current
 * device already saved, and adopting a code makes it the active backup for
 * future pushes.
 */
@Serializable
private data class BackupPayload(val favorites: List<String> = emptyList())

object BackupCode {

    /** No ambiguous characters (0/O, 1/I/l) so codes are easy to read aloud. */
    const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    const val LENGTH = 8

    private val rng = SecureRandom()

    fun generate(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
    }

    /** The server accepts 6-12 lowercase letters/digits. */
    fun isValid(code: String): Boolean = code.matches(Regex("^[a-z0-9]{6,12}$"))
}

class FavoritesBackup(private val settings: SettingsStore, private val baseUrlProvider: () -> String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_CODE = "backup_code"

    /** The install's backup code, created on first use. */
    fun activeCode(): String {
        val existing = settings.getString(KEY_CODE, null)
        if (!existing.isNullOrBlank() && BackupCode.isValid(existing)) return existing
        val fresh = BackupCode.generate()
        settings.putString(KEY_CODE, fresh)
        return fresh
    }

    fun setActiveCode(code: String) {
        if (BackupCode.isValid(code)) settings.putString(KEY_CODE, code)
    }

    private fun base(): String = baseUrlProvider().trimEnd('/')

    /** Pulls favorites saved under [code] (empty when the server has none). */
    suspend fun pull(code: String): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val result = ApiClient.get("${base()}/api/v1/backup/$code")
            if (result.code !in 200..299) return@withContext emptySet()
            json.decodeFromString(BackupPayload.serializer(), result.body).favorites.toSet()
        }.getOrDefault(emptySet())
    }

    /** Pushes [favorites] under the active code. True when the server stored them. */
    suspend fun push(favorites: Set<String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val code = activeCode()
            val body = json.encodeToString(BackupPayload.serializer(), BackupPayload(favorites.toList()))
            val result = ApiClient.putJson("${base()}/api/v1/backup/$code", body)
            result.code in 200..299
        }.getOrDefault(false)
    }

    /**
     * Merges the current device's favorites with a backup under [code] (union),
     * adopts [code] as the active backup, and pushes the merged set back so the
     * other device sees everything too. Returns the merged favorites.
     */
    suspend fun restore(code: String, local: Set<String>): Set<String> {
        val remote = pull(code)
        val merged = local + remote
        // Adopt the code FIRST so the merged set lands on the restored backup
        // (push targets the active code) and the other device sees it too.
        setActiveCode(code)
        if (merged.isNotEmpty()) {
            runCatching { push(merged) }
        }
        return merged
    }

    companion object {
        const val KEY_BACKUP_CODE = "backup_code"
    }
}
