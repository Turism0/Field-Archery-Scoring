package uk.co.fieldarcheryscoring.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.fieldarcheryscoring.data.remote.AuthResult
import uk.co.fieldarcheryscoring.data.remote.FieldArcheryApi
import uk.co.fieldarcheryscoring.data.remote.UsernameCheck
import uk.co.fieldarcheryscoring.domain.AccountRole
import uk.co.fieldarcheryscoring.domain.DeviceDataRules

/**
 * A sign-in waiting for the archer to say what happens to rounds on this phone that aren't on the account (3.2.02;
 * 3.2.09): scored while nobody was signed in, or an earlier account with the same username (deleted, or the server lost
 * it). [question] is what to ask.
 */
class PendingSignIn internal constructor(internal val result: AuthResult, internal val plan: DeviceDataRules.SignInPlan) {
    val username: String get() = result.username
    val roundCount: Int get() = plan.total
    val question: String get() = DeviceDataRules.signInQuestion(plan, result.username)
}

/** After signing in: a recovery code to show once (new account or reset), or a question to ask first ([pending]). */
data class SignInStep(val recoveryCode: String?, val pending: PendingSignIn?)

/** Signing in and out. Optional: the app works fully offline without an account. */
class AccountRepository(
    private val api: FieldArcheryApi,
    private val store: AccountStore,
    private val profiles: ProfileRepository,
    /** Whose rounds are on this phone (3.2.02; 3.2.09 put away, not wiped). */
    private val device: DeviceData,
    /** Called after signing in, e.g. to upload rounds scored before signing in. */
    private val onSignedIn: () -> Unit = {},
) {
    val account: StateFlow<Account?> = store.account
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Rounds on a phone signed in before 3.2.02 belong to that account (and its username is remembered).
        store.account.value?.let { a ->
            if (device.owner == null) device.owner = a.archerId
            device.rememberName(a.archerId, a.username)
        }
        // The server ended the sign-in (revoked or expired): put that account's rounds away, as signing out does.
        store.onCleared = { id -> scope.launch { if (device.owner == id) device.putAway(id) } }
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
        val a = store.account.value ?: throw uk.co.fieldarcheryscoring.data.remote.ApiException(401, "not_signed_in", "Sign in first.")
        api.deleteAccount(a.token, password)
        device.owner = null
        device.forgetName(a.archerId)
        device.clearSession()
        store.clear()
    }

    /** A new recovery code (the old one stops working). */
    suspend fun newRecoveryCode(password: String): String {
        val token = store.account.value?.token ?: throw uk.co.fieldarcheryscoring.data.remote.ApiException(401, "not_signed_in", "Sign in first.")
        return api.newRecoveryCode(token, password)
    }

    suspend fun login(username: String, password: String): SignInStep =
        begin(api.login(username.trim(), password, deviceName()))

    /**
     * Rounds already here: this account's come back (and any showing for another account are put away first);
     * signed-out ones and an earlier account with this username are asked about.
     */
    private suspend fun begin(result: AuthResult): SignInStep {
        val plan = device.planSignIn(result.archerId, result.username)
        plan.hideFirst?.let { device.putAway(it) }
        if (plan.needsAsking) return SignInStep(result.recoveryCode, PendingSignIn(result, plan))
        device.bringBack(result.archerId)
        signedIn(result)
        return SignInStep(result.recoveryCode, null)
    }

    /** The answer to [PendingSignIn]: [keep] adds those rounds to this account (backed up again); otherwise they're removed. */
    suspend fun finishSignIn(pending: PendingSignIn, keep: Boolean) {
        device.settle(pending.plan.earlier, keep, pending.result.archerId)
        device.bringBack(pending.result.archerId)
        signedIn(pending.result)
    }

    /** Cancelled at the question: not signed in here, and the server forgets the sign-in. */
    suspend fun cancelSignIn(pending: PendingSignIn) {
        runCatching { api.logout(pending.result.token) }
    }

    /** What removing your rounds at sign-out would lose, as the dialog's warning; null when nothing would. */
    suspend fun signOutWarning(): String? = device.signOutLoss().let { (waiting, inProgress) -> DeviceDataRules.signOutWarning(waiting, inProgress) }

    /**
     * Signs out on this phone (3.2.09, Pete): your rounds, profile and Stats filters are put away, hidden from anyone
     * else, and come back when you sign in here again. With [remove], they're deleted from this phone instead (they come
     * back from the server). Tells the server too if there's signal; signs out here either way.
     */
    suspend fun signOut(remove: Boolean = false) {
        val current = store.account.value ?: return
        runCatching { api.logout(current.token) }
        if (remove) device.removeShowing(current.archerId) else device.putAway(current.archerId)
        store.clear()
    }

    private suspend fun signedIn(result: AuthResult) {
        device.owner = result.archerId
        device.rememberName(result.archerId, result.username)
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
