package uk.co.fnurble.fieldarchery.data

import android.os.Build
import kotlinx.coroutines.flow.StateFlow
import uk.co.fnurble.fieldarchery.data.remote.AuthResult
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.data.remote.UsernameCheck
import uk.co.fnurble.fieldarchery.domain.AccountRole

/** Signing in and out. Optional: the app works fully offline without an account. */
class AccountRepository(
    private val api: FieldArcheryApi,
    private val store: AccountStore,
    private val profiles: ProfileRepository,
    /** Called after signing in, e.g. to upload rounds scored before signing in. */
    private val onSignedIn: () -> Unit = {},
) {
    val account: StateFlow<Account?> = store.account

    suspend fun checkUsername(username: String): UsernameCheck = api.checkUsername(username.trim())

    suspend fun register(username: String, password: String) =
        signedIn(api.register(username.trim(), password, deviceName()))

    suspend fun login(username: String, password: String) =
        signedIn(api.login(username.trim(), password, deviceName()))

    /** Signs out on this phone. Tells the server too if there's signal; signs out locally either way. */
    suspend fun signOut() {
        val current = store.account.value ?: return
        runCatching { api.logout(current.token) }
        store.clear()
    }

    private suspend fun signedIn(result: AuthResult) {
        store.save(Account(result.archerId, result.username, result.token, result.expiresUtc))
        profiles.setUsername(result.username)
        // Role now if possible, so the Admin menu is there straight away; the next sync tries again anyway.
        runCatching {
            val me = api.me(result.token)
            store.setRole(AccountRole.fromCode(me.optString("role")))
            store.setDuplicateLocations(me.optInt("duplicateLocations"))
        }
        onSignedIn()
    }

    /** Shown on the server's list of signed-in phones, e.g. "Google Pixel 8". */
    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
            .replaceFirstChar { it.uppercase() }
}
