package uk.co.fnurble.nfas.data

import org.json.JSONObject
import uk.co.fnurble.nfas.data.remote.ApiException
import uk.co.fnurble.nfas.data.remote.NfasApi
import uk.co.fnurble.nfas.domain.AccountRole
import java.time.Instant

data class AdminArcher(
    val archerId: String,
    val username: String,
    val role: AccountRole,
    val createdUtc: Instant?,
    val lastLoginUtc: Instant?,
)

/**
 * Admin menu, straight from the server (nothing stored on the phone). Needs signal.
 * The server checks the role on every call; failures are [ApiException]s with a message to show.
 */
class AdminRepository(private val api: NfasApi, private val accounts: AccountStore) {

    /** Every account: Owner first, then Admins, then Users, by username. */
    suspend fun archers(): List<AdminArcher> = call { token ->
        val list = api.adminArchers(token).getJSONArray("archers")
        (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            AdminArcher(
                archerId = a.getString("archerId"),
                username = a.getString("username"),
                role = AccountRole.fromCode(a.getString("role")),
                createdUtc = a.instantOrNull("createdUtc"),
                lastLoginUtc = a.instantOrNull("lastLoginUtc"),
            )
        }
    }

    /** Owner only: make an account an Admin, or back to a User. */
    suspend fun setAdmin(archerId: String, admin: Boolean) = call { token ->
        api.setRole(token, archerId, if (admin) AccountRole.ADMIN.name else AccountRole.USER.name)
    }

    private suspend fun <T> call(block: suspend (String) -> T): T {
        val token = accounts.account.value?.token
            ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) first.")
        try {
            return block(token)
        } catch (e: ApiException) {
            when {
                e.status == 401 -> accounts.clear()
                // No longer an admin: hide the menu now rather than at the next sync.
                e.code == "not_admin" -> accounts.setRole(AccountRole.USER)
                e.code == "not_owner" -> accounts.setRole(AccountRole.ADMIN)
            }
            throw e
        }
    }
}

private fun JSONObject.instantOrNull(name: String): Instant? =
    if (isNull(name)) null else runCatching { Instant.parse(getString(name)) }.getOrNull()
