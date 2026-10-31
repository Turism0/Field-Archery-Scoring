package uk.co.fnurble.nfas.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    fun save(account: Account) {
        prefs.edit()
            .putString(K_ARCHER, account.archerId)
            .putString(K_USERNAME, account.username)
            .putString(K_TOKEN, encrypt(account.token))
            .putString(K_EXPIRES, account.expiresUtc)
            .apply()
        _account.value = account
    }

    fun clear() {
        prefs.edit().clear().apply()
        _account.value = null
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
    }
}
