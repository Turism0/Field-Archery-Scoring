package uk.co.fnurble.fieldarchery.data

import org.json.JSONArray
import org.json.JSONObject
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import java.time.LocalDate

/**
 * A shoot location. [myRounds] = your finished rounds there; [rounds] = everyone's.
 * [latitude] / [longitude] null for a name-only location (3.0: no map pin, e.g. a Nationals years ago).
 */
data class MapLocation(
    val id: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val isPrivate: Boolean,
    val myRounds: Int,
    val rounds: Int,
    /** On a location page (3.0): you (its creator or an Admin) can put this name-only location on the map. */
    val canPlace: Boolean = false,
) {
    val hasPin: Boolean get() = latitude != null && longitude != null

    /** "Name (private)", "Name (no map pin)" for a pick list. */
    val label: String get() = name + listOfNotNull(if (isPrivate) "private" else null, if (hasPin) null else "no map pin")
        .joinToString("") { " ($it)" }
}

data class LocationFriend(
    val archerId: String,
    val username: String,
    /** BIG_GAME or WOODSMAN: a friend who's shot both has a row for each. */
    val roundType: String = "BIG_GAME",
    val rounds: Int,
    val bestTotal: Int?,
    val averagePerAnimal: Double?,
    val roundsList: List<FriendRoundSummary>,
    /** Average per animal × 36 and × 40: what a whole 36- or 40-target round averages. */
    val average36: Double? = null,
    val average40: Double? = null,
)

/** Everyone's finished rounds at a location for one bow style (no names). */
data class BowStyleStats(
    val roundType: String = "BIG_GAME",
    val bowStyle: String,
    val archers: Int,
    val rounds: Int,
    val bestTotal: Int?,
    val averageTotal: Double?,
    val averagePerAnimal: Double?,
    /** Average per animal × 36 and × 40. */
    val average36: Double? = null,
    val average40: Double? = null,
    /** Set on the per-class rows (server 2.4); null on the all-classes rows. */
    val archerClass: ArcherClass? = null,
    val ageGroup: AgeGroup? = null,
)

data class LocationPage(
    val location: MapLocation,
    val myRounds: List<FriendRoundSummary>,
    val friends: List<LocationFriend>,
    val bowStyles: List<BowStyleStats>,
    /** The same per class and age group, for the class filter (empty from servers before 2.4). */
    val bowClasses: List<BowStyleStats> = emptyList(),
)

/** Map and location pages, straight from the server (nothing stored on the phone). Needs signal. */
class LocationPageRepository(private val api: FieldArcheryApi, private val accounts: AccountStore) {

    /**
     * A new location at a point picked on the map (not tied to a round). If the name is already used,
     * the [ApiException] (409 name_taken) says so.
     */
    suspend fun create(name: String, lat: Double, lon: Double, isPrivate: Boolean) {
        call { token -> api.createLocation(token, name.trim(), lat, lon, isPrivate) }
    }

    /** The locations on the map (with a pin). */
    suspend fun map(): List<MapLocation> = call { token ->
        api.locationMap(token).getJSONArray("locations").objects().map { it.toMapLocation() }.filter { it.hasPin }
    }

    /** Name-only locations (3.0): no map pin. An older server without them gives an empty list. */
    suspend fun named(): List<MapLocation> = call { token ->
        try {
            api.namedLocations(token).getJSONArray("locations").objects().map { it.toMapLocation() }
        } catch (e: ApiException) {
            if (e.status == 404) emptyList() else throw e
        }
    }

    /** Every location you can pick for a round, by name: on the map and name-only. */
    suspend fun known(): List<MapLocation> = (map() + named()).sortedBy { it.name.lowercase() }

    /** A new name-only location (3.0). 409 name_taken as [create]. Returns its id and name. */
    suspend fun createNamed(name: String, isPrivate: Boolean): Pair<String, String> = call { token ->
        val j = api.createNamedLocation(token, name.trim(), isPrivate)
        j.getString("locationId") to j.getString("name")
    }

    /** Puts a name-only location on the map (its creator or an Admin). */
    suspend fun place(locationId: String, lat: Double, lon: Double) {
        call { token -> api.placeLocation(token, locationId, lat, lon) }
    }

    suspend fun page(locationId: String): LocationPage = call { token ->
        val json = api.locationPage(token, locationId)
        val location = json.getJSONObject("location")
        LocationPage(
            location = MapLocation(
                id = location.getString("locationId"),
                name = location.getString("name"),
                latitude = location.doubleOrNull("latitude"),
                longitude = location.doubleOrNull("longitude"),
                isPrivate = location.optBoolean("isPrivate"),
                myRounds = 0,
                rounds = 0,
                canPlace = location.optBoolean("canPlace"),
            ),
            myRounds = json.getJSONArray("myRounds").objects().map { it.toRoundSummary() },
            friends = json.getJSONArray("friends").objects().map { f ->
                LocationFriend(
                    archerId = f.getString("archerId"),
                    username = f.getString("username"),
                    roundType = f.optString("roundType", "BIG_GAME"),
                    rounds = f.getInt("rounds"),
                    bestTotal = f.intOrNull("bestTotal"),
                    averagePerAnimal = f.doubleOrNull("averagePerAnimal"),
                    average36 = f.doubleOrNull("average36"),
                    average40 = f.doubleOrNull("average40"),
                    roundsList = f.getJSONArray("roundsList").objects().map { it.toRoundSummary() },
                )
            },
            bowStyles = json.getJSONArray("bowStyles").objects().map { it.toBowStats() },
            bowClasses = json.optJSONArray("bowClasses")?.objects().orEmpty().map { b ->
                b.toBowStats().copy(
                    archerClass = if (b.optString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS,
                    ageGroup = runCatching { AgeGroup.valueOf(b.getString("ageGroup")) }.getOrNull(),
                )
            },
        )
    }

    private fun JSONObject.toBowStats(): BowStyleStats {
        val b = this
        return BowStyleStats(
            roundType = b.optString("roundType", "BIG_GAME"),
            bowStyle = b.getString("bowStyle"),
            archers = b.getInt("archers"),
            rounds = b.getInt("rounds"),
            bestTotal = b.intOrNull("bestTotal"),
            averageTotal = b.doubleOrNull("averageTotal"),
            averagePerAnimal = b.doubleOrNull("averagePerAnimal"),
            average36 = b.doubleOrNull("average36"),
            average40 = b.doubleOrNull("average40"),
        )
    }

    private suspend fun <T> call(block: suspend (String) -> T): T {
        val token = accounts.account.value?.token
            ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to see shoot locations.")
        try {
            return block(token)
        } catch (e: ApiException) {
            if (e.status == 401) accounts.clear()
            throw e
        }
    }
}

private fun JSONObject.toMapLocation() = MapLocation(
    id = getString("locationId"),
    name = getString("name"),
    latitude = doubleOrNull("latitude"),
    longitude = doubleOrNull("longitude"),
    isPrivate = optBoolean("isPrivate"),
    myRounds = optInt("myRounds"),
    rounds = optInt("rounds"),
)

private fun JSONObject.toRoundSummary(): FriendRoundSummary {
    val cls = if (getString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS
    val age = AgeGroup.valueOf(getString("ageGroup"))
    val bow = BowStyle.fromCode(getString("bowStyle"))?.code ?: getString("bowStyle")
    return FriendRoundSummary(
        roundId = getString("roundId"),
        shootDate = LocalDate.parse(getString("shootDate")),
        targetCount = getInt("targetCount"),
        classLine = (if (optString("roundType") == "WOODSMAN") "Woodsman · " else "") +
            "${cls.displayName} ${age.displayName} $bow",
        totalPoints = getInt("totalPoints"),
        targetsLine = targetsLine(),
    ).withHandicapFields(this)
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
// Missing (older server) counts as null too.
private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)
private fun JSONObject.doubleOrNull(name: String): Double? = if (isNull(name)) null else getDouble(name)
