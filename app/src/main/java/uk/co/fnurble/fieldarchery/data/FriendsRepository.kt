package uk.co.fnurble.fieldarchery.data

import org.json.JSONObject
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.data.sync.toRoundData
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.PersonalHandicap
import uk.co.fnurble.fieldarchery.domain.Round
import uk.co.fnurble.fieldarchery.domain.targetsDescription
import java.time.LocalDate

data class FriendSummary(
    val archerId: String,
    val username: String,
    val rounds: Int,
    val bestTotal: Int?,
    val lastShootDate: LocalDate?,
    /** Out of 24; predator/prey = 2 animals, fan = n. Rounds / best / average are Big Game. */
    val averagePerAnimal: Double?,
    val woodsmanRounds: Int = 0,
    val woodsmanBest: Int? = null,
    /**
     * Their latest finished Big Game rounds with every target scored, up to 20 per bow style (server 2.9): their
     * personal handicaps on the friends list.
     */
    val recent: List<PersonalHandicap.Entry> = emptyList(),
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
    /** "36 targets · 37 animals (1 predator/prey)" (servers before 2.0.02 just give the number of targets). */
    val targetsLine: String = "$targetCount targets",
    // For the handicap (2.2.05): Big Game rounds only.
    val isBigGame: Boolean = true,
    val archerClass: ArcherClass? = null,
    val ageGroup: AgeGroup? = null,
    val bowStyle: BowStyle? = null,
    /** Animals in the round (predator/prey = 2, fan = n); 0 if the server didn't say (then targets are used). */
    val animalCount: Int = 0,
)

/** Extra fields for the handicap from a server round summary. */
internal fun FriendRoundSummary.withHandicapFields(r: JSONObject): FriendRoundSummary = copy(
    isBigGame = r.optString("roundType", "BIG_GAME") != "WOODSMAN",
    archerClass = if (r.optString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS,
    ageGroup = runCatching { AgeGroup.valueOf(r.getString("ageGroup")) }.getOrNull(),
    bowStyle = BowStyle.fromCode(r.optString("bowStyle")),
    animalCount = r.optInt("animalCount", 0),
)

/** "36 targets · 37 animals (1 predator/prey)" from a server round summary. */
internal fun JSONObject.targetsLine(): String {
    val targets = getInt("targetCount")
    return if (optString("roundType") == "WOODSMAN" || !has("animalCount")) targetsDescription(targets, targets, 0, 0)
    else targetsDescription(targets, optInt("animalCount"), optInt("predatorPrey"), optInt("fans"))
}

/** A round a friend scored for you in their group, waiting for you to accept or decline. */
data class RoundOffer(
    val roundId: String,
    val fromUsername: String,
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
class FriendsRepository(
    private val api: FieldArcheryApi,
    private val accounts: AccountStore,
    /** Asks the background sync to run (an accepted round then downloads to this phone). */
    private val onAccepted: () -> Unit = {},
) {

    suspend fun overview(): FriendsOverview = call { token ->
        val json = api.friends(token)
        val overview = FriendsOverview(
            friends = json.getJSONArray("friends").objects().map { f ->
                FriendSummary(
                    archerId = f.getString("archerId"),
                    username = f.getString("username"),
                    rounds = f.getInt("rounds"),
                    bestTotal = f.intOrNull("bestTotal"),
                    lastShootDate = f.stringOrNull("lastShootDate")?.let { LocalDate.parse(it.take(10)) },
                    averagePerAnimal = if (f.isNull("averagePerAnimal")) null else f.getDouble("averagePerAnimal"),
                    woodsmanRounds = f.optInt("woodsmanRounds"),
                    woodsmanBest = f.intOrNull("woodsmanBest"),
                    // Only rounds with every target scored count (servers before 2.9 don't say: none then).
                    recent = f.optJSONArray("recent")?.objects().orEmpty().mapIndexedNotNull { i, r ->
                        runCatching {
                            val targets = r.getInt("targetCount")
                            val bow = BowStyle.fromCode(r.getString("bowStyle"))
                            if (bow == null || r.optString("roundType", "BIG_GAME") == "WOODSMAN" || !r.has("targetsScored") ||
                                r.getInt("targetsScored") != targets || targets <= 0) null
                            // Newest first from the server: the order tells apart rounds on the same day.
                            else PersonalHandicap.Entry(LocalDate.parse(r.getString("shootDate").take(10)), (1_000_000 - i).toLong(), bow, r.getInt("totalPoints"), targets)
                        }.getOrNull()
                    },
                )
            },
            incoming = json.getJSONArray("incoming").objects().map { FriendRequest(it.getString("archerId"), it.getString("username")) },
            outgoing = json.getJSONArray("outgoing").objects().map { FriendRequest(it.getString("archerId"), it.getString("username")) },
        )
        // Remembered for adding friends to a group round with no signal.
        accounts.setCachedFriends(overview.friends.map { CachedFriend(it.archerId, it.username) })
        overview
    }

    /** Rounds friends have scored for you, waiting for an answer (newest first). Updates the Friends tile badge. */
    suspend fun offers(): List<RoundOffer> = call { token ->
        val list = api.offers(token).getJSONArray("received").objects().map { o ->
            val cls = if (o.getString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS
            val age = AgeGroup.valueOf(o.getString("ageGroup"))
            val bow = BowStyle.fromCode(o.getString("bowStyle"))?.code ?: o.getString("bowStyle")
            RoundOffer(
                roundId = o.getString("roundId"),
                fromUsername = o.getString("fromUsername"),
                shootDate = LocalDate.parse(o.getString("shootDate").take(10)),
                targetCount = o.getInt("targetCount"),
                classLine = (if (o.optString("roundType") == "WOODSMAN") "Woodsman · " else "") +
                    "${cls.displayName} ${age.displayName} $bow",
                totalPoints = o.getInt("totalPoints"),
                locationName = o.stringOrNull("locationName"),
            )
        }
        accounts.setRoundOffers(list.size)
        list
    }

    /** The round in a request, to look at before answering. */
    suspend fun offerRound(roundId: String): Round = call { token ->
        val json = api.offer(token, roundId)
        val offer = json.getJSONObject("offer")
        val round = json.getJSONObject("round")
            // The stored round doesn't carry its own id or server fields; add what the reader needs.
            .put("roundId", roundId)
            .put("isDeleted", false)
            .put("serverUpdatedUtc", offer.optString("updatedUtc"))
        if (round.isNull("locationName") && !offer.isNull("locationName")) round.put("locationName", offer.getString("locationName"))
        round.toRoundData().toDomain()
    }

    /** Accept: the round becomes yours (it downloads to this phone on the next sync). */
    suspend fun acceptOffer(roundId: String) = call { token ->
        api.acceptOffer(token, roundId)
        accounts.setRoundOffers((accounts.roundOffers.value - 1).coerceAtLeast(0))
        onAccepted()
    }

    suspend fun declineOffer(roundId: String) = call { token ->
        api.declineOffer(token, roundId)
        accounts.setRoundOffers((accounts.roundOffers.value - 1).coerceAtLeast(0))
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
                classLine = (if (r.optString("roundType") == "WOODSMAN") "Woodsman · " else "") +
                    "${cls.displayName} ${age.displayName} $bow",
                totalPoints = r.getInt("totalPoints"),
                locationName = r.stringOrNull("locationName"),
                targetsLine = r.targetsLine(),
            ).withHandicapFields(r)
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
