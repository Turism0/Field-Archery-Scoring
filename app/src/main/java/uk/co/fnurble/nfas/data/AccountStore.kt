package uk.co.fnurble.nfas.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.fnurble.nfas.domain.AccountRole
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A friend as remembered on this phone for adding to group rounds offline. */
data class CachedFriend(val archerId: String, val username: String)

/** The signed-in account on this phone. The password is never stored, only the API token. */
data class Account(val archerId: String, val username: String, val token: String, val expiresUtc: String)

/**
 * Keeps the sign-in token, encrypted with a key held in the Android Keystore (it never leaves the phone).
 * Stored in its own preferences file, which is excluded from backups (res/xml backup rules), so a
 * restored phone simply asks the archer to sign in again.
 */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _lastSync = MutableStateFlow(prefs.getLong(K_LAST_SYNC, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    /** When a sync last completed (upload and download), shown on the home screen. */
    val lastSync: StateFlow<Instant?> = _lastSync.asStateFlow()

    private val _role = MutableStateFlow(AccountRole.fromCode(prefs.getString(K_ROLE, null)))
    /**
     * This account's role, refreshed from the server on sign-in and every sync. Only decides what the
     * app shows (the Admin menu); the server checks the role itself on every admin call.
     */
    val role: StateFlow<AccountRole> = _role.asStateFlow()

    private val _duplicateLocations = MutableStateFlow(0)
    /** Admins: possible duplicate shoot locations waiting for review (badge on the Admin tile). Not stored. */
    val duplicateLocations: StateFlow<Int> = _duplicateLocations.asStateFlow()

    fun setDuplicateLocations(count: Int) { _duplicateLocations.value = count }

    private val _roundOffers = MutableStateFlow(0)
    /** Rounds friends have scored for you, waiting to be accepted (badge on the Friends tile). Not stored. */
    val roundOffers: StateFlow<Int> = _roundOffers.asStateFlow()

    fun setRoundOffers(count: Int) { _roundOffers.value = count }

    /**
     * Friends' usernames and ids from the last time the list was fetched, so friends can be added to a group round
     * with no signal. Usernames only (the server's own data), cleared on sign-out.
     */
    fun cachedFriends(): List<CachedFriend> = try {
        val arr = org.json.JSONArray(prefs.getString(K_FRIENDS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { CachedFriend(it.getString("id"), it.getString("u")) }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    fun setCachedFriends(friends: List<CachedFriend>) {
        val arr = org.json.JSONArray()
        friends.forEach { arr.put(org.json.JSONObject().put("id", it.archerId).put("u", it.username)) }
        prefs.edit().putString(K_FRIENDS, arr.toString()).apply()
    }

    fun setRole(role: AccountRole) {
        prefs.edit().putString(K_ROLE, role.name).apply()
        _role.value = role
    }

    fun setLastSync(time: Instant) {
        prefs.edit().putLong(K_LAST_SYNC, time.toEpochMilli()).apply()
        _lastSync.value = time
    }

    fun save(account: Account) {
        prefs.edit()
            .putString(K_ARCHER, account.archerId)
            .putString(K_USERNAME, account.username)
            .putString(K_TOKEN, encrypt(account.token))
            .putString(K_EXPIRES, account.expiresUtc)
            .apply()
        _account.value = account
    }

    /** Server time of the newest round change already downloaded (ISO-8601), or null for "everything". */
    fun pullCursor(): String? = prefs.getString(K_PULL_CURSOR, null)

    fun setPullCursor(serverUpdatedUtc: String) {
        prefs.edit().putString(K_PULL_CURSOR, serverUpdatedUtc).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _account.value = null
        _lastSync.value = null
        _role.value = AccountRole.USER
        _duplicateLocations.value = 0
        _roundOffers.value = 0
    }

    private fun load(): Account? = try {
        val token = prefs.getString(K_TOKEN, null)?.let(::decrypt)
        val archerId = prefs.getString(K_ARCHER, null)
        val username = prefs.getString(K_USERNAME, null)
        if (token != null && archerId != null && username != null) {
            Account(archerId, username, token, prefs.getString(K_EXPIRES, null).orEmpty())
        } else {
            null
        }
    } catch (e: Exception) {
        // Key missing or data from another phone (e.g. an old backup): sign out cleanly.
        prefs.edit().clear().apply()
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val FILE = "nfas_account"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nfas_account_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val K_ARCHER = "archer_id"
        const val K_USERNAME = "username"
        const val K_TOKEN = "token"
        const val K_EXPIRES = "expires_utc"
        const val K_PULL_CURSOR = "pull_cursor"
        const val K_LAST_SYNC = "last_sync"
        const val K_ROLE = "role"
        const val K_FRIENDS = "friends_cache"
    }
}
