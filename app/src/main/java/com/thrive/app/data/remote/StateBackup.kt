package com.thrive.app.data.remote

import com.thrive.app.BuildConfig
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.BudgetState
import com.thrive.app.data.model.PantryItem
import com.thrive.app.data.model.ShoppingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.SecureRandom

/**
 * Free, anonymous backup for the app's user state — saved deals, pantry items,
 * and the budget/shopping list. Each install generates an 8-char code shown in
 * Settings; state is pushed to the Thrive sync server under that code, and
 * entering the same code on another phone merges everything there — no account,
 * no API key, no email. The code is the only credential.
 *
 * Merge semantics are add-only unions: restoring never deletes what the current
 * device already saved. Writes use the server's optimistic-concurrency protocol
 * (If-Match with the last-read revision); a 409 triggers a re-pull, re-merge,
 * and retry so concurrent devices never silently overwrite each other.
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

/**
 * Backup carries the user's code in the request URL, so it must never travel
 * over plain HTTP except to the loopback interface (debug/emulator
 * development). HTTPS is always allowed; the emulator host alias (10.0.2.2) is
 * only allowed in debug builds.
 */
object BackupPolicy {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")

    fun isPermitted(baseUrl: String, allowEmulatorAlias: Boolean): Boolean {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return false
        val scheme = trimmed.substringBefore("://").lowercase()
        val rest = trimmed.substringAfter("://", "")
        val host = rest.substringBefore("/").substringBefore(":").lowercase().removePrefix("[").removeSuffix("]")
        if (scheme == "https") return true
        if (scheme != "http") return false
        if (host in loopbackHosts) return true
        if (allowEmulatorAlias && host == "10.0.2.2") return true
        return false
    }
}

/** Outcome of reading a backup: every failure mode is distinct and visible. */
sealed class PullResult {
    data class Found(val snapshot: BackupSnapshot, val revision: String) : PullResult()
    /** The code is valid and the server answered, but no backup exists yet. */
    data class Empty(val revision: String?) : PullResult()
    object InvalidCode : PullResult()
    object Unauthorized : PullResult()
    data class HttpError(val code: Int, val message: String) : PullResult()
    object NetworkFailure : PullResult()
    object ParseFailure : PullResult()
    object NotPermitted : PullResult()
}

/** Outcome of writing a backup. */
sealed class PushResult {
    data class Ok(val revision: String) : PushResult()
    /** Still conflicting after re-pull/merge retries — never force-overwrite. */
    data class Conflict(val currentRevision: String) : PushResult()
    object InvalidCode : PushResult()
    object Unauthorized : PushResult()
    data class HttpError(val code: Int, val message: String) : PushResult()
    object NetworkFailure : PushResult()
    object ParseFailure : PushResult()
    object NotPermitted : PushResult()
}

/** Outcome of a full restore. Failed carries a user-ready reason. */
sealed class RestoreResult {
    data class Restored(val snapshot: BackupSnapshot) : RestoreResult()
    data class Failed(val reason: String) : RestoreResult()
}

class StateBackup(private val settings: SettingsStore, private val baseUrlProvider: () -> String) {

    // encodeDefaults=false so section pushes omit untouched sections (empty
    // favorites/pantry, null budget) and the server keeps the stored copy.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val KEY_CODE = "backup_code"

    /** Last-known server revision for the active code; drives If-Match. */
    @Volatile private var lastRevision: String? = null

    /** The install's backup code, created on first use. */
    fun activeCode(): String {
        val existing = settings.getString(KEY_CODE, null)
        if (!existing.isNullOrBlank() && BackupCode.isValid(existing)) return existing
        val fresh = BackupCode.generate()
        settings.putString(KEY_CODE, fresh)
        return fresh
    }

    fun setActiveCode(code: String) {
        if (BackupCode.isValid(code)) {
            settings.putString(KEY_CODE, code)
            lastRevision = null
        }
    }

    private fun base(): String = baseUrlProvider().trimEnd('/')

    private fun permitted(): Boolean = BackupPolicy.isPermitted(base(), BuildConfig.DEBUG)

    private fun parseRevision(body: String): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        (root["revision"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Pulls the full snapshot saved under [code]. Every failure is distinct. */
    suspend fun pull(code: String): PullResult = withContext(Dispatchers.IO) {
        if (!BackupCode.isValid(code)) return@withContext PullResult.InvalidCode
        if (!permitted()) return@withContext PullResult.NotPermitted
        runCatching {
            val result = ApiClient.get("${base()}/api/v1/backup/$code")
            when (result.code) {
                in 200..299 -> {
                    runCatching {
                        val snapshot = json.decodeFromString(BackupSnapshot.serializer(), result.body)
                        val revision = parseRevision(result.body) ?: result.etag?.trim('"')
                        lastRevision = revision
                        if (revision == null) PullResult.Empty(null) else PullResult.Found(snapshot, revision)
                    }.getOrElse { PullResult.ParseFailure }
                }
                400 -> PullResult.InvalidCode
                401, 403 -> PullResult.Unauthorized
                404 -> PullResult.Empty(null)
                else -> PullResult.HttpError(result.code, result.body.take(200))
            }
        }.getOrElse { PullResult.NetworkFailure }
    }

    /**
     * Pushes [payload] under [code] with optimistic concurrency: If-Match the
     * last-read revision, and on 409 re-pull → merge add-only → retry (up to 3
     * attempts). Returns [PushResult.Ok] only on a confirmed server write.
     */
    private suspend fun put(code: String, payloadIn: BackupSnapshot, knownRevision: String? = null): PushResult =
        withContext(Dispatchers.IO) {
            if (!BackupCode.isValid(code)) return@withContext PushResult.InvalidCode
            if (!permitted()) return@withContext PushResult.NotPermitted
            var payload = payloadIn
            var attempt = 0
            while (attempt < 3) {
                val rev = knownRevision ?: lastRevision
                val ifMatch = rev ?: "*"
                val body = json.encodeToString(BackupSnapshot.serializer(), payload)
                val result = runCatching {
                    ApiClient.putJson("${base()}/api/v1/backup/$code", body, ifMatch)
                }.getOrElse { return@withContext PushResult.NetworkFailure }
                when (result.code) {
                    in 200..299 -> {
                        val newRev = parseRevision(result.body) ?: rev ?: ""
                        lastRevision = newRev
                        return@withContext PushResult.Ok(newRev)
                    }
                    409 -> {
                        // Someone else wrote in the meantime — re-pull, merge,
                        // and retry so no update is lost.
                        val remote = pull(code)
                        when (remote) {
                            is PullResult.Found -> {
                                payload = mergeForPush(remote.snapshot, payload)
                                lastRevision = remote.revision
                                attempt++
                            }
                            is PullResult.Empty -> {
                                lastRevision = null
                                attempt++
                            }
                            else -> return@withContext PushResult.HttpError(409, "conflict and re-pull failed")
                        }
                    }
                    400 -> return@withContext PushResult.InvalidCode
                    401, 403 -> return@withContext PushResult.Unauthorized
                    404 -> return@withContext PushResult.HttpError(404, "no backup exists for this code")
                    else -> return@withContext PushResult.HttpError(result.code, result.body.take(200))
                }
            }
            PushResult.Conflict(lastRevision ?: "")
        }

    private fun mergeForPush(remote: BackupSnapshot, local: BackupSnapshot): BackupSnapshot {
        val favorites =
            if (local.favorites.isEmpty()) remote.favorites
            else BackupMerge.favorites(remote.favorites, local.favorites)
        val pantry = BackupMerge.pantry(remote.pantry, local.pantry)
        val budget = when {
            local.budget == null -> remote.budget
            remote.budget == null -> local.budget
            else -> BackupMerge.budget(local.budget, remote.budget)
        }
        return BackupSnapshot(favorites = favorites, pantry = pantry, budget = budget)
    }

    // ---- Section pushes (server merges per-section; absent sections are kept) ----

    suspend fun pushFavorites(favorites: Set<String>): PushResult =
        put(activeCode(), BackupSnapshot(favorites = favorites))

    suspend fun pushPantry(pantry: List<PantryItem>): PushResult =
        put(activeCode(), BackupSnapshot(pantry = pantry))

    suspend fun pushBudget(budget: BudgetState): PushResult =
        put(activeCode(), BackupSnapshot(budget = budget))

    suspend fun pushAll(snapshot: BackupSnapshot): PushResult = put(activeCode(), snapshot)

    /**
     * Merges the current device's snapshot with a backup under [code] (add-only
     * unions per section), adopts [code] ONLY after the server confirmed the
     * merge was written, and returns the merged snapshot. On any failure the
     * active code is left untouched and nothing is written.
     */
    suspend fun restore(code: String, local: BackupSnapshot): RestoreResult {
        val cleaned = code.trim().lowercase()
        if (!BackupCode.isValid(cleaned)) {
            return RestoreResult.Failed("That doesn't look like a backup code (6-12 letters/numbers).")
        }
        return when (val pulled = pull(cleaned)) {
            is PullResult.Found -> {
                val merged = BackupSnapshot(
                    favorites = BackupMerge.favorites(local.favorites, pulled.snapshot.favorites),
                    pantry = BackupMerge.pantry(local.pantry, pulled.snapshot.pantry),
                    budget = when {
                        local.budget == null && pulled.snapshot.budget == null -> null
                        local.budget == null -> pulled.snapshot.budget
                        pulled.snapshot.budget == null -> local.budget
                        else -> BackupMerge.budget(local.budget, pulled.snapshot.budget)
                    },
                )
                when (val push = put(cleaned, merged, pulled.revision)) {
                    is PushResult.Ok -> {
                        setActiveCode(cleaned)
                        RestoreResult.Restored(merged)
                    }
                    is PushResult.NotPermitted ->
                        RestoreResult.Failed("Backup is off on this connection — use a secure (HTTPS) sync server.")
                    else ->
                        RestoreResult.Failed("Couldn't reach the backup server. Nothing was changed.")
                }
            }
            is PullResult.Empty -> {
                // Valid empty backup: adopt the code and create it with local data.
                when (val push = put(cleaned, local)) {
                    is PushResult.Ok -> {
                        setActiveCode(cleaned)
                        RestoreResult.Restored(local)
                    }
                    is PushResult.NotPermitted ->
                        RestoreResult.Failed("Backup is off on this connection — use a secure (HTTPS) sync server.")
                    else ->
                        RestoreResult.Failed("Couldn't reach the backup server. Nothing was changed.")
                }
            }
            is PullResult.InvalidCode -> RestoreResult.Failed("That doesn't look like a backup code (6-12 letters/numbers).")
            is PullResult.Unauthorized -> RestoreResult.Failed("The backup server rejected this request (unauthorized).")
            is PullResult.NotPermitted -> RestoreResult.Failed("Backup is off on this connection — use a secure (HTTPS) sync server.")
            is PullResult.NetworkFailure -> RestoreResult.Failed("Couldn't reach the backup server — check your connection.")
            is PullResult.ParseFailure -> RestoreResult.Failed("The backup server returned unreadable data.")
            is PullResult.HttpError -> RestoreResult.Failed("Backup server error (${pulled.code}). Nothing was changed.")
        }
    }

    companion object {
        const val KEY_BACKUP_CODE = "backup_code"
    }
}
