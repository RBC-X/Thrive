package com.thrive.app.data.remote

import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.BudgetState
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.ShoppingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * Free, anonymous backup for the app's user state — saved deals, pantry items,
 * and the budget/shopping list. Each install generates an 8-char code shown in
 * Settings; state is pushed to the Thrive sync server under that code, and
 * entering the same code on another phone merges everything there — no account,
 * no API key, no email. The code is the only credential.
 *
 * Merge semantics are add-only unions: restoring never deletes what the current
 * device already saved, and adopting a code makes it the active backup for
 * future pushes. Section pushes are per-section (the server replaces only the
 * sections present in a PUT body), so an older app version pushing favorites
 * alone never wipes a device's pantry or budget.
 */
@Serializable
data class BackupSnapshot(
    val favorites: Set<String> = emptySet(),
    val pantry: List<PantryItem> = emptyList(),
    val budget: BudgetState? = null,
)

/** Pure merge rules — add-only unions so devices never delete each other's data. */
object BackupMerge {

    /** Union: every favorite from either device survives. */
    fun favorites(local: Set<String>, remote: Set<String>): Set<String> = local + remote

    /**
     * Union by stable id: local items keep their own versions (the current
     * device's edits are never clobbered); remote-only items are appended.
     */
    fun pantry(local: List<PantryItem>, remote: List<PantryItem>): List<PantryItem> {
        val seen = local.map { it.id }.toMutableSet()
        return local + remote.filterNot { it.id in seen }
    }

    /**
     * Budget merges items add-only like pantry. The budget amount and people
     * count adopt the local value when it is set, otherwise the remote one.
     */
    fun budget(local: BudgetState, remote: BudgetState?): BudgetState {
        if (remote == null) return local
        return BudgetState(
            budget = if (local.budget > 0) local.budget else remote.budget,
            people = if (local.people > 1) local.people else remote.people,
            items = shopping(local.items, remote.items),
        )
    }

    private fun shopping(local: List<ShoppingItem>, remote: List<ShoppingItem>): List<ShoppingItem> {
        val seen = local.map { it.id }.toMutableSet()
        return local + remote.filterNot { it.id in seen }
    }
}

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

class StateBackup(private val settings: SettingsStore, private val baseUrlProvider: () -> String) {

    // encodeDefaults=false so section pushes omit untouched sections (empty
    // favorites/pantry, null budget) and the server keeps the stored copy.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

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

    /** Pulls the full snapshot saved under [code] (empty when the server has none). */
    suspend fun pull(code: String): BackupSnapshot = withContext(Dispatchers.IO) {
        runCatching {
            val result = ApiClient.get("${base()}/api/v1/backup/$code")
            if (result.code !in 200..299) return@withContext BackupSnapshot()
            json.decodeFromString(BackupSnapshot.serializer(), result.body)
        }.getOrDefault(BackupSnapshot())
    }

    private suspend fun put(payload: BackupSnapshot): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val code = activeCode()
            val body = json.encodeToString(BackupSnapshot.serializer(), payload)
            val result = ApiClient.putJson("${base()}/api/v1/backup/$code", body)
            result.code in 200..299
        }.getOrDefault(false)
    }

    // ---- Section pushes (server merges per-section; absent sections are kept) ----

    suspend fun pushFavorites(favorites: Set<String>): Boolean = put(BackupSnapshot(favorites = favorites))

    suspend fun pushPantry(pantry: List<PantryItem>): Boolean = put(BackupSnapshot(pantry = pantry))

    suspend fun pushBudget(budget: BudgetState): Boolean = put(BackupSnapshot(budget = budget))

    suspend fun pushAll(snapshot: BackupSnapshot): Boolean = put(snapshot)

    /**
     * Merges the current device's snapshot with a backup under [code] (add-only
     * unions per section), adopts [code] as the active backup, and pushes the
     * merged snapshot back so the other device sees everything too. Returns the
     * merged snapshot.
     */
    suspend fun restore(code: String, local: BackupSnapshot): BackupSnapshot {
        val remote = pull(code)
        val merged = BackupSnapshot(
            favorites = BackupMerge.favorites(local.favorites, remote.favorites),
            pantry = BackupMerge.pantry(local.pantry, remote.pantry),
            budget = when {
                local.budget == null && remote.budget == null -> null
                local.budget == null -> remote.budget
                remote.budget == null -> local.budget
                else -> BackupMerge.budget(local.budget, remote.budget)
            },
        )
        // Adopt the code FIRST so the merged snapshot lands on the restored
        // backup (push targets the active code) and the other device sees it.
        setActiveCode(code)
        runCatching { pushAll(merged) }
        return merged
    }

    companion object {
        const val KEY_BACKUP_CODE = "backup_code"
    }
}
