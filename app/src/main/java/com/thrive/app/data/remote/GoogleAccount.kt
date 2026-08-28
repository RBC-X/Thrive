package com.thrive.app.data.remote

import com.thrive.app.BuildConfig
import com.thrive.app.data.local.AndroidKeystoreSecureValueStore
import com.thrive.app.data.local.SecureValueStore
import com.thrive.app.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Google Sign-In backup: the user signs in with their Google account and the
 * app's saved deals / pantry / budget sync under that account instead of an
 * 8-character backup code. The app sends Google's ID token to the Thrive
 * backend, which verifies it (Google's public tokeninfo endpoint — no secret
 * on the server) and stores state under a stable key derived from the
 * account's subject id. Signing into the same Google account on any device
 * pulls the same state.
 *
 * Google Sign-In is hidden entirely when the build has no GOOGLE_CLIENT_ID
 * configured (see app/build.gradle.kts) — the app keeps working with the
 * existing code-based backup and no backup.
 */
@Serializable
data class GoogleAccountInfo(
    val sub: String = "",
    val name: String = "",
    val email: String = "",
    val picture: String = "",
    val accountKey: String = "",
)

@Serializable
data class SessionTokens(
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessTokenExpiresAt: Long = 0L,
) {
    fun isUsable(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && refreshToken.isNotBlank() && accessTokenExpiresAt > now + 30_000L
}

@Serializable
private data class AuthExchangeResponse(
    val sub: String = "",
    val name: String = "",
    val email: String = "",
    val picture: String = "",
    val accountKey: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessTokenExpiresAt: Long = 0L,
) {
    fun account() = GoogleAccountInfo(sub, name, email, picture, accountKey)
    fun session() = SessionTokens(accessToken, refreshToken, accessTokenExpiresAt)
}

object GoogleAccountStore {
    private const val KEY_SUB = "google_sub"
    private const val KEY_NAME = "google_name"
    private const val KEY_EMAIL = "google_email"
    private const val KEY_PICTURE = "google_picture"
    private const val KEY_ACCOUNT_KEY = "google_account_key"
    private const val KEY_REVISION_PREFIX = "google_backup_revision_"

    fun save(settings: SettingsStore, info: GoogleAccountInfo) {
        settings.putString(KEY_SUB, info.sub)
        settings.putString(KEY_NAME, info.name)
        settings.putString(KEY_EMAIL, info.email)
        settings.putString(KEY_PICTURE, info.picture)
        settings.putString(KEY_ACCOUNT_KEY, info.accountKey)
    }

    fun load(settings: SettingsStore): GoogleAccountInfo {
        val sub = settings.getString(KEY_SUB, null) ?: return GoogleAccountInfo()
        return GoogleAccountInfo(
            sub = sub,
            name = settings.getString(KEY_NAME, "") ?: "",
            email = settings.getString(KEY_EMAIL, "") ?: "",
            picture = settings.getString(KEY_PICTURE, "") ?: "",
            accountKey = settings.getString(KEY_ACCOUNT_KEY, "") ?: "",
        )
    }

    fun clear(settings: SettingsStore) {
        val accountKey = settings.getString(KEY_ACCOUNT_KEY, "").orEmpty()
        if (accountKey.isNotBlank()) settings.remove(KEY_REVISION_PREFIX + accountKey)
        settings.remove(KEY_SUB)
        settings.remove(KEY_NAME)
        settings.remove(KEY_EMAIL)
        settings.remove(KEY_PICTURE)
        settings.remove(KEY_ACCOUNT_KEY)
    }

    fun revision(settings: SettingsStore, accountKey: String): String? =
        accountKey.takeIf { it.isNotBlank() }
            ?.let { settings.getString(KEY_REVISION_PREFIX + it, null) }

    fun saveRevision(settings: SettingsStore, accountKey: String, revision: String?) {
        if (accountKey.isBlank()) return
        val key = KEY_REVISION_PREFIX + accountKey
        if (revision.isNullOrBlank()) settings.remove(key) else settings.putString(key, revision)
    }
}

/** True when this build is configured for Google Sign-In. */
fun googleSignInConfigured(): Boolean = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

/** Outcome of the auth exchange with the backend. */
sealed class GoogleAuthResult {
    data class Ok(val account: GoogleAccountInfo) : GoogleAuthResult()
    data class Failed(val reason: String) : GoogleAuthResult()
}

class GoogleBackup(
    private val settings: SettingsStore,
    private val baseUrlProvider: () -> String,
    private val client: JsonHttpClient = ApiClient,
    private val secureStore: SecureValueStore = AndroidKeystoreSecureValueStore(settings),
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The signed-in Google account, or an empty info when not signed in. */
    fun account(): GoogleAccountInfo = secureStore.get(KEY_SECURE_ACCOUNT)?.let { raw ->
        runCatching { json.decodeFromString(GoogleAccountInfo.serializer(), raw) }.getOrNull()
    } ?: GoogleAccountStore.load(settings) // one-release compatibility with existing installs

    fun isSignedIn(): Boolean = account().sub.isNotBlank() && session() != null

    /** Clears the local Google identity (does not touch the server backup). */
    fun signOut() {
        secureStore.remove(KEY_SECURE_ACCOUNT)
        secureStore.remove(KEY_SECURE_SESSION)
        GoogleAccountStore.clear(settings)
    }

    fun session(): SessionTokens? = secureStore.get(KEY_SECURE_SESSION)?.let { raw ->
        runCatching { json.decodeFromString(SessionTokens.serializer(), raw) }.getOrNull()
    }

    private fun base(): String = baseUrlProvider().trimEnd('/')

    private fun parseRevision(body: String): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        (root["revision"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun revision(): String? = GoogleAccountStore.revision(settings, account().accountKey)

    private fun saveRevision(value: String?) =
        GoogleAccountStore.saveRevision(settings, account().accountKey, value)

    /**
     * Exchanges a Google ID token for the account identity the backend accepts
     * (profile + the stable accountKey). Called right after Google Sign-In so
     * the app learns the account key before any backup request.
     */
    suspend fun exchange(token: String): GoogleAuthResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext GoogleAuthResult.Failed("Google sign-in returned no ID token.")
        val body = json.encodeToString(ExchangeRequest.serializer(), ExchangeRequest(token))
        val result = runCatching {
            client.postJson("${base()}/api/v1/auth/google", body)
        }.getOrElse { return@withContext GoogleAuthResult.Failed("Couldn't reach the backup server — check your connection.") }
        when {
            result.code in 200..299 -> runCatching {
                val response = json.decodeFromString(AuthExchangeResponse.serializer(), result.body)
                val info = response.account()
                require(info.sub.isNotBlank() && info.accountKey.isNotBlank())
                secureStore.put(KEY_SECURE_ACCOUNT, json.encodeToString(GoogleAccountInfo.serializer(), info))
                val session = response.session()
                if (session.accessToken.isNotBlank() && session.refreshToken.isNotBlank()) {
                    secureStore.put(KEY_SECURE_SESSION, json.encodeToString(SessionTokens.serializer(), session))
                }
                // Remove legacy plaintext identity after a successful exchange.
                GoogleAccountStore.clear(settings)
                GoogleAuthResult.Ok(info)
            }.getOrElse { GoogleAuthResult.Failed("The backup server returned an unreadable account response.") }
            result.code == 401 -> GoogleAuthResult.Failed("Google rejected the sign-in — try again.")
            else -> GoogleAuthResult.Failed("Backup server error (${result.code}). Try again in a moment.")
        }
    }

    /** Refreshes the opaque app session without retaining Google's ID token. */
    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val current = session() ?: return@withContext false
        if (current.refreshToken.isBlank()) return@withContext false
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(current.refreshToken))
        val result = runCatching { client.postJson("${base()}/api/v1/auth/refresh", body) }
            .getOrElse { return@withContext false }
        if (result.code !in 200..299) {
            if (result.code == 401 || result.code == 403) secureStore.remove(KEY_SECURE_SESSION)
            return@withContext false
        }
        return@withContext runCatching {
            val refreshed = json.decodeFromString(SessionTokens.serializer(), result.body)
            require(refreshed.accessToken.isNotBlank() && refreshed.refreshToken.isNotBlank())
            secureStore.put(KEY_SECURE_SESSION, json.encodeToString(SessionTokens.serializer(), refreshed))
            true
        }.getOrDefault(false)
    }

    /** Revokes the refresh token server-side, then always clears local secrets. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val current = session()
        if (current != null) {
            val body = json.encodeToString(LogoutRequest.serializer(), LogoutRequest(current.refreshToken))
            runCatching {
                client.postJson("${base()}/api/v1/auth/logout", body, token = current.accessToken)
            }
        }
        signOut()
    }

    /** Permanently removes the encrypted server account, then clears this device. */
    suspend fun deleteAccount(): Boolean = withContext(Dispatchers.IO) {
        var token = accessToken() ?: return@withContext false
        var result = runCatching { client.delete("${base()}/api/v1/account", token) }
            .getOrElse { return@withContext false }
        if ((result.code == 401 || result.code == 403) && refreshSession()) {
            token = session()?.accessToken ?: return@withContext false
            result = runCatching { client.delete("${base()}/api/v1/account", token) }
                .getOrElse { return@withContext false }
        }
        if (result.code !in 200..299) return@withContext false
        signOut()
        true
    }

    private suspend fun accessToken(): String? {
        val current = session() ?: return null
        if (current.isUsable()) return current.accessToken
        if (!refreshSession()) return null
        return session()?.accessToken
    }

    /** Pulls the full snapshot stored under the signed-in account. */
    suspend fun pull(token: String): PullResult = withContext(Dispatchers.IO) {
        if (account().sub.isBlank()) return@withContext PullResult.Unauthorized
        pullAuthorized(accessToken() ?: token)
    }

    /** Pull using the encrypted app session, refreshing once when necessary. */
    suspend fun pull(): PullResult = withContext(Dispatchers.IO) {
        if (account().sub.isBlank()) return@withContext PullResult.Unauthorized
        val token = accessToken() ?: return@withContext PullResult.Unauthorized
        val first = pullAuthorized(token)
        if (first !is PullResult.Unauthorized || !refreshSession()) first
        else session()?.accessToken?.let { pullAuthorized(it) } ?: PullResult.Unauthorized
    }

    private suspend fun pullAuthorized(token: String): PullResult {
        return runCatching {
            val result = client.get("${base()}/api/v1/account/backup", token = token)
            when (result.code) {
                in 200..299 -> runCatching {
                    val snapshot = json.decodeFromString(BackupSnapshot.serializer(), result.body)
                    val foundRevision = parseRevision(result.body) ?: result.etag?.trim('"')
                    saveRevision(foundRevision)
                    if (foundRevision == null) PullResult.Empty(null)
                    else PullResult.Found(snapshot, foundRevision)
                }.getOrElse { PullResult.ParseFailure }
                401, 403 -> PullResult.Unauthorized
                404 -> PullResult.Empty(null)
                else -> PullResult.HttpError(result.code, result.body.take(200))
            }
        }.getOrElse { PullResult.NetworkFailure }
    }

    /** Pushes a full snapshot with persisted optimistic-concurrency revisions. */
    suspend fun push(token: String, payload: BackupSnapshot): PushResult = withContext(Dispatchers.IO) {
        if (account().sub.isBlank()) return@withContext PushResult.Unauthorized
        pushAuthorized(accessToken() ?: token, payload)
    }

    /** Push using the encrypted app session, refreshing once when necessary. */
    suspend fun push(payload: BackupSnapshot): PushResult = withContext(Dispatchers.IO) {
        if (account().sub.isBlank()) return@withContext PushResult.Unauthorized
        val token = accessToken() ?: return@withContext PushResult.Unauthorized
        val first = pushAuthorized(token, payload)
        if (first !is PushResult.Unauthorized || !refreshSession()) first
        else session()?.accessToken?.let { pushAuthorized(it, payload) } ?: PushResult.Unauthorized
    }

    private suspend fun pushAuthorized(token: String, payload: BackupSnapshot): PushResult {
        var mergedPayload = payload
        var attempt = 0
        while (attempt < 3) {
            val knownRevision = revision()
            val body = json.encodeToString(BackupSnapshot.serializer(), mergedPayload)
            val result = runCatching {
                client.putJson(
                    "${base()}/api/v1/account/backup",
                    body,
                    ifMatch = knownRevision ?: "*",
                    token = token,
                )
            }.getOrElse { return PushResult.NetworkFailure }
            when {
                result.code in 200..299 -> {
                    val newRevision = parseRevision(result.body)
                        ?: result.etag?.trim('"')
                        ?: knownRevision
                        ?: ""
                    saveRevision(newRevision)
                    return PushResult.Ok(newRevision)
                }
                result.code == 401 || result.code == 403 -> return PushResult.Unauthorized
                result.code == 409 -> {
                    when (val remote = pullAuthorized(token)) {
                        is PullResult.Found -> {
                            mergedPayload = merge(remote.snapshot, mergedPayload)
                            attempt++
                        }
                        is PullResult.Empty -> {
                            saveRevision(null)
                            attempt++
                        }
                        is PullResult.Unauthorized -> return PushResult.Unauthorized
                        is PullResult.NetworkFailure -> return PushResult.NetworkFailure
                        else -> return PushResult.HttpError(409, "conflict and re-pull failed")
                    }
                }
                else -> return PushResult.HttpError(result.code, result.body.take(200))
            }
        }
        return PushResult.Conflict(revision().orEmpty())
    }

    private fun merge(remote: BackupSnapshot, local: BackupSnapshot): BackupSnapshot = BackupSnapshot(
        favorites = BackupMerge.favorites(remote.favorites, local.favorites) -
            (remote.deletedFavoriteIds + local.deletedFavoriteIds),
        recipeFavorites = BackupMerge.favorites(remote.recipeFavorites, local.recipeFavorites) -
            (remote.deletedRecipeFavoriteIds + local.deletedRecipeFavoriteIds),
        pantry = BackupMerge.pantry(local.pantry, remote.pantry).filterNot {
            it.id in (remote.deletedPantryItemIds + local.deletedPantryItemIds)
        },
        budget = when {
            local.budget == null -> remote.budget
            remote.budget == null -> local.budget
            else -> BackupMerge.budget(local.budget, remote.budget)
        }?.let { budget ->
            budget.copy(items = budget.items.filterNot {
                it.id in (remote.deletedShoppingItemIds + local.deletedShoppingItemIds)
            })
        },
        householdProfile = BackupMerge.householdProfile(local.householdProfile, remote.householdProfile),
        seenDealIds = BackupMerge.seenDealIds(local.seenDealIds, remote.seenDealIds),
        feedRevision = local.feedRevision ?: remote.feedRevision,
        deletedFavoriteIds = remote.deletedFavoriteIds + local.deletedFavoriteIds,
        deletedRecipeFavoriteIds = remote.deletedRecipeFavoriteIds + local.deletedRecipeFavoriteIds,
        deletedPantryItemIds = remote.deletedPantryItemIds + local.deletedPantryItemIds,
        deletedShoppingItemIds = remote.deletedShoppingItemIds + local.deletedShoppingItemIds,
    )

    private companion object {
        const val KEY_SECURE_ACCOUNT = "google_account"
        const val KEY_SECURE_SESSION = "google_session"
    }
}

@Serializable
private data class ExchangeRequest(val idToken: String)

@Serializable
private data class RefreshRequest(val refreshToken: String)

@Serializable
private data class LogoutRequest(val refreshToken: String)
