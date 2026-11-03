package uk.co.fnurble.fieldarchery.data

import org.json.JSONObject
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.domain.AccountRole
import java.time.Instant

data class AdminArcher(
    val archerId: String,
    val username: String,
    val role: AccountRole,
    val createdUtc: Instant?,
    val lastLoginUtc: Instant?,
)

data class AdminLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdUtc: Instant?,
    val createdBy: String?,
    val rounds: Int,
    val isPrivate: Boolean = false,
)

/** Two locations that might be the same place. [reasons]: nearby, same_name, similar_name. */
data class DuplicatePair(val a: AdminLocation, val b: AdminLocation, val distanceMetres: Int, val reasons: List<String>)

/**
 * Admin menu, straight from the server (nothing stored on the phone). Needs signal.
 * The server checks the role on every call; failures are [ApiException]s with a message to show.
 */
class AdminRepository(private val api: FieldArcheryApi, private val accounts: AccountStore) {

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

    /** Live shoot locations, by name. */
    suspend fun locations(): List<AdminLocation> = call { token ->
        val list = api.adminLocations(token).getJSONArray("locations")
        (0 until list.length()).map { i ->
            val l = list.getJSONObject(i)
            AdminLocation(
                id = l.getString("locationId"),
                name = l.getString("name"),
                latitude = l.getDouble("latitude"),
                longitude = l.getDouble("longitude"),
                createdUtc = l.instantOrNull("createdUtc"),
                createdBy = if (l.isNull("createdBy")) null else l.getString("createdBy"),
                rounds = l.getInt("rounds"),
                isPrivate = l.optBoolean("isPrivate"),
            )
        }
    }

    /** Possible duplicates, most likely first. Also updates the badge count. */
    suspend fun duplicates(): List<DuplicatePair> = call { token ->
        val list = api.duplicateLocations(token).getJSONArray("pairs")
        (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val reasons = p.getJSONArray("reasons")
            DuplicatePair(
                a = p.getJSONObject("a").toLocation(),
                b = p.getJSONObject("b").toLocation(),
                distanceMetres = p.getInt("distanceMetres"),
                reasons = (0 until reasons.length()).map { reasons.getString(it) },
            )
        }.also { accounts.setDuplicateLocations(it.size) }
    }

    /** The two are different places: stop flagging them. */
    suspend fun approve(pair: DuplicatePair) = call { token -> api.approveLocationPair(token, pair.a.id, pair.b.id) }

    suspend fun renameLocation(id: String, name: String, isPrivate: Boolean? = null) =
        call { token -> api.renameLocation(token, id, name.trim(), isPrivate) }

    /** Returns how many rounds moved. */
    suspend fun mergeLocation(id: String, intoId: String): Int = call { token ->
        api.mergeLocation(token, id, intoId).optInt("roundsMoved")
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

private fun JSONObject.toLocation() = AdminLocation(
    id = getString("locationId"),
    name = getString("name"),
    latitude = getDouble("latitude"),
    longitude = getDouble("longitude"),
    createdUtc = null,
    createdBy = if (isNull("createdBy")) null else getString("createdBy"),
    rounds = getInt("rounds"),
    isPrivate = optBoolean("isPrivate"),
)

private fun JSONObject.instantOrNull(name: String): Instant? =
    if (isNull(name)) null else runCatching { Instant.parse(getString(name)) }.getOrNull()
