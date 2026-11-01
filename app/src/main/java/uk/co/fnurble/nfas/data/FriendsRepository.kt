package uk.co.fnurble.nfas.data

import org.json.JSONObject
import uk.co.fnurble.nfas.data.remote.ApiException
import uk.co.fnurble.nfas.data.remote.NfasApi
import uk.co.fnurble.nfas.data.sync.toRoundData
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.Round
import java.time.LocalDate

data class FriendSummary(
    val archerId: String,
    val username: String,
    val rounds: Int,
    val bestTotal: Int?,
    val lastShootDate: LocalDate?,
    /** Out of 24; predator/prey = 2 animals, fan = n. */
    val averagePerAnimal: Double?,
)

data class FriendRequest(val archerId: String, val username: String)

data class FriendsOverview(
    val friends: List<FriendSummary>,
    val incoming: List<FriendRequest>,
    val outgoing: List<FriendRequest>,
)

data class FriendRoundSummary(
    val roundId: String,
    val shootDate: LocalDate,
    val targetCount: Int,
    val classLine: String,
    val totalPoints: Int,
    val locationName: String? = null,
)

/**
 * Friends, straight from the server each time (nothing about friends is stored on the phone).
 * Needs signal and a signed-in account; failures are [ApiException]s with a message to show.
 */
class FriendsRepository(private val api: NfasApi, private val accounts: AccountStore) {

    suspend fun overview(): FriendsOverview = call { token ->
        val json = api.friends(token)
        FriendsOverview(
            friends = json.getJSONArray("friends").objects().map { f ->
                FriendSummary(
                    archerId = f.getString("archerId"),
                    username = f.getString("username"),
                    rounds = f.getInt("rounds"),
                    bestTotal = f.intOrNull("bestTotal"),
                    lastShootDate = f.stringOrNull("lastShootDate")?.let { LocalDate.parse(it.take(10)) },
                    averagePerAnimal = if (f.isNull("averagePerAnimal")) null else f.getDouble("averagePerAnimal"),
                )
            },
            incoming = json.getJSONArray("incoming").objects().map { FriendRequest(it.getString("archerId"), it.getString("username")) },
            outgoing = json.getJSONArray("outgoing").objects().map { FriendRequest(it.getString("archerId"), it.getString("username")) },
        )
    }

    /** Returns the server's message, e.g. "Friend request sent to …" or "You and … are now friends." */
    suspend fun sendRequest(username: String): String = call { token ->
        api.sendFriendRequest(token, username.trim()).optString("message", "Friend request sent.")
    }

    suspend fun accept(archerId: String) = call { token -> api.acceptFriend(token, archerId) }

    /** Decline, cancel or unfriend. */
    suspend fun remove(archerId: String) = call { token -> api.removeFriend(token, archerId) }

    suspend fun rounds(archerId: String): List<FriendRoundSummary> = call { token ->
        api.friendRounds(token, archerId).getJSONArray("rounds").objects().map { r ->
            val cls = if (r.getString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS
            val age = AgeGroup.valueOf(r.getString("ageGroup"))
            val bow = BowStyle.fromCode(r.getString("bowStyle"))?.code ?: r.getString("bowStyle")
            FriendRoundSummary(
                roundId = r.getString("roundId"),
                shootDate = LocalDate.parse(r.getString("shootDate")),
                targetCount = r.getInt("targetCount"),
                classLine = "${cls.displayName} ${age.displayName} $bow",
                totalPoints = r.getInt("totalPoints"),
                locationName = r.stringOrNull("locationName"),
            )
        }
    }

    suspend fun round(archerId: String, roundId: String): Round = call { token ->
        api.friendRound(token, archerId, roundId).toRoundData().toDomain()
    }

    private suspend fun <T> call(block: suspend (String) -> T): T {
        val token = accounts.account.value?.token
            ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to use Friends.")
        try {
            return block(token)
        } catch (e: ApiException) {
            // Sign-in no longer valid: sign out here so Profile asks to sign in again.
            if (e.status == 401) accounts.clear()
            throw e
        }
    }
}

private fun org.json.JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)
private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
