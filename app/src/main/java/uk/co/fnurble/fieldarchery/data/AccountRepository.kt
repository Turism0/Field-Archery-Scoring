package uk.co.fnurble.fieldarchery.data

import android.os.Build
import kotlinx.coroutines.flow.StateFlow
import uk.co.fnurble.fieldarchery.data.remote.AuthResult
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.data.remote.UsernameCheck
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.domain.DeviceDataRules

/**
 * A sign-in waiting for the archer to say what happens to the rounds on this phone scored while nobody was signed in
 * (3.2.02): add them to this account, or remove them. [roundCount] of them.
 */
class PendingSignIn internal constructor(internal val result: AuthResult, val roundCount: Int) {
    val username: String get() = result.username
}

/** After signing in: a recovery code to show once (new account or reset), or a question to ask first ([pending]). */
data class SignInStep(val recoveryCode: String?, val pending: PendingSignIn?)

/** Signing in and out. Optional: the app works fully offline without an account. */
class AccountRepository(
    private val api: FieldArcheryApi,
    private val store: AccountStore,
    private val profiles: ProfileRepository,
    /** Whose rounds are on this phone (3.2.02). */
    private val device: DeviceData,
    /** Called after signing in, e.g. to upload rounds scored before signing in. */
    private val onSignedIn: () -> Unit = {},
) {
    val account: StateFlow<Account?> = store.account

    init {
        // Rounds on a phone signed in before 3.2.02 belong to that account.
        if (device.owner == null) store.account.value?.let { device.owner = it.archerId }
    }

    suspend fun checkUsername(username: String): UsernameCheck = api.checkUsername(username.trim())

    /** Creates the account and signs in (its recovery code, server 2.4, is in the step, to show once). */
    suspend fun register(username: String, password: String): SignInStep =
        begin(api.register(username.trim(), password, deviceName()))

    /**
     * Forgotten password (2.4): with the recovery code or a reset code from an Admin. Signs in here (every other phone is
     * signed out); the new recovery code is in the step, to show once.
     */
    suspend fun resetPassword(username: String, code: String, newPassword: String): SignInStep =
        begin(api.resetPassword(username.trim(), code.trim(), newPassword, deviceName()))

    /**
     * Deletes the account and everything the server keeps about it (2.4.02, Google Play needs this), then signs out
     * here. Rounds on this phone stay, belonging to nobody: the next sign-in asks about them.
     */
    suspend fun deleteAccount(password: String) {
        val token = store.account.value?.token ?: throw uk.co.fnurble.fieldarchery.data.remote.ApiException(401, "not_signed_in", "Sign in first.")
        api.deleteAccount(token, password)
        store.clear()
        device.owner = null
    }

    /** A new recovery code (the old one stops working). */
    suspend fun newRecoveryCode(password: String): String {
        val token = store.account.value?.token ?: throw uk.co.fnurble.fieldarchery.data.remote.ApiException(401, "not_signed_in", "Sign in first.")
        return api.newRecoveryCode(token, password)
    }

    suspend fun login(username: String, password: String): SignInStep =
        begin(api.login(username.trim(), password, deviceName()))

    /** Rounds already here: this account's stay, another account's go, signed-out ones are asked about. */
    private suspend fun begin(result: AuthResult): SignInStep {
        val count = device.ownRoundCount()
        when (DeviceDataRules.signInData(device.owner, result.archerId, count)) {
            DeviceDataRules.SignInData.ASK -> return SignInStep(result.recoveryCode, PendingSignIn(result, count))
            DeviceDataRules.SignInData.CLEAR -> device.clear(everything = true)
            DeviceDataRules.SignInData.KEEP -> {}
        }
        signedIn(result)
        return SignInStep(result.recoveryCode, null)
    }

    /** The answer to [PendingSignIn]: [keep] adds the rounds to this account; otherwise they're removed first. */
    suspend fun finishSignIn(pending: PendingSignIn, keep: Boolean) {
        if (keep) device.claimRounds() else device.clear(everything = false)
        signedIn(pending.result)
    }

    /** Cancelled at the question: not signed in here, and the server forgets the sign-in. */
    suspend fun cancelSignIn(pending: PendingSignIn) {
        runCatching { api.logout(pending.result.token) }
    }

    /** What signing out would lose, as the dialog's warning; null when nothing would. */
    suspend fun signOutWarning(): String? = device.signOutLoss().let { (waiting, inProgress) -> DeviceDataRules.signOutWarning(waiting, inProgress) }

    /**
     * Signs out on this phone and clears it (3.2.02, Pete): rounds, profile, friends and the rest, so the next person
     * starts clean; your rounds come back from the server when you sign in again. Tells the server too if there's
     * signal; signs out here either way.
     */
    suspend fun signOut() {
        val current = store.account.value ?: return
        runCatching { api.logout(current.token) }
        store.clear()
        device.clear(everything = true)
    }

    private suspend fun signedIn(result: AuthResult) {
        device.owner = result.archerId
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
