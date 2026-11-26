package uk.co.fieldarcheryscoring.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import uk.co.fieldarcheryscoring.data.local.RoundDao
import uk.co.fieldarcheryscoring.data.remote.ApiException
import uk.co.fieldarcheryscoring.data.remote.FieldArcheryApi
import uk.co.fieldarcheryscoring.data.local.LocationState
import uk.co.fieldarcheryscoring.domain.KnownPlace
import uk.co.fieldarcheryscoring.domain.LocationMatch
import uk.co.fieldarcheryscoring.domain.NearbyLocation
import uk.co.fieldarcheryscoring.domain.PendingPlace
import uk.co.fieldarcheryscoring.domain.PlaceMatch
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Shoot locations for rounds (roadmap step 2b).
 *  - When a round starts, takes one GPS fix and keeps it on the phone with the round.
 *  - With signal and an account, asks the server for a location within the catchment (about a quarter mile):
 *    found → the round gets that location; none → the archer is asked to name it (once).
 *  - No signal: the fix waits and is looked up on the next sync.
 * The phone's own position is never uploaded with a round; it's only sent to ask what's nearby.
 * 3.2.25 (Pete): a copy of every location you can use is kept on the phone ([PlaceCache]), so with no signal the round is
 * still matched, Nearby and Known locations still work, and a new name can be given (made on the server when there's
 * signal). A new name is first checked for locations that might be the same one (same or similar name, or within 300 m):
 * [SimilarExists] offers them instead.
 */
class LocationRepository(
    private val context: Context,
    private val dao: RoundDao,
    private val api: FieldArcheryApi,
    private val accounts: AccountStore,
    /** Called after a change that may need uploading. */
    private val onChanged: () -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    /** 3.2.25: the phone's copy of the locations you can use. */
    val cache: PlaceCache? = null,
) {
    /** Work that must outlive the screen that started it (the fix after "Start round"). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lookupLock = Mutex()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Takes a fix for a new round in the background, then looks it up if possible. */
    fun captureFor(roundId: String) {
        if (!hasPermission()) return
        scope.launch {
            val fix = currentFix() ?: return@launch
            dao.setFix(roundId, fix.latitude, fix.longitude)
            runCatching { lookUpPending() }
        }
    }

    /** "Get position now" from the location sheet, for a round with no fix. Returns true if one was taken. */
    suspend fun captureNow(roundId: String): Boolean {
        if (!hasPermission()) return false
        val fix = currentFix() ?: return false
        dao.setFix(roundId, fix.latitude, fix.longitude)
        runCatching { lookUpPending() }
        return true
    }

    /**
     * Looks up every round whose position hasn't been matched yet. Needs an account. With signal the server answers;
     * with none (3.2.25), the phone's copy of the locations: the nearest within the catchment, or none known there (asked
     * to name it). Called after a fix and on every sync.
     */
    suspend fun lookUpPending() = lookupLock.withLock {
        val token = accounts.account.value?.token ?: return@withLock
        var offline = false
        for (round in dao.pendingLocationLookups()) {
            val lat = round.fixLat!!
            val lon = round.fixLon!!
            if (!offline) {
                try {
                    val json = api.nearbyLocations(token, lat, lon)
                    val nearest = json.getJSONArray("locations").optJSONObject(0)
                    if (nearest != null) {
                        dao.setLocation(round.id, nearest.getString("locationId"), nearest.getString("name"), now())
                        onChanged()
                    } else {
                        dao.markNeedsName(round.id)
                    }
                    continue
                } catch (e: ApiException) {
                    if (!e.isNetwork) throw e
                    offline = true
                }
            }
            val known = cache?.places?.value.orEmpty()
            if (known.isEmpty()) continue   // nothing on the phone yet: wait for signal
            val nearest = LocationMatch.nearby(known, lat, lon, LocationMatch.CATCHMENT_METRES).firstOrNull()
            if (nearest != null) {
                dao.setLocation(round.id, nearest.id, nearest.name, now())
                onChanged()
            } else {
                dao.markNeedsName(round.id)
            }
        }
    }

    /** 3.2.25: refreshes the phone's copy of the locations you can use (on the map and name-only). Called on every sync. */
    suspend fun refreshCache() {
        val c = cache ?: return
        val token = accounts.account.value?.token ?: return
        // 3.2.31: others' private locations (the Owner's map only) are never matched or offered, so not kept here.
        fun JSONArray.places() = (0 until length()).map { getJSONObject(it) }.filter { !it.optBoolean("othersPrivate") }.map { o ->
            KnownPlace(o.getString("locationId"), o.getString("name"),
                if (!o.has("latitude") || o.isNull("latitude")) null else o.getDouble("latitude"),
                if (!o.has("longitude") || o.isNull("longitude")) null else o.getDouble("longitude"),
                o.optBoolean("isPrivate"))
        }
        val pinned = api.locationMap(token).getJSONArray("locations").places().filter { it.hasPin }
        val named = try {
            api.namedLocations(token).getJSONArray("locations").places()
        } catch (e: ApiException) {
            if (e.status == 404) emptyList() else throw e
        }
        if (accounts.account.value?.token == token) c.replace((pinned + named).distinctBy { it.id })
    }

    /** Picks up renamed and merged locations for every round on the phone. */
    suspend fun refreshNames() {
        val token = accounts.account.value?.token ?: return
        val ids = dao.locationIds()
        if (ids.isEmpty()) return
        val list = api.resolveLocations(token, ids).getJSONArray("locations")
        for (i in 0 until list.length()) {
            val l = list.getJSONObject(i)
            dao.refreshLocation(l.getString("requestedId"), l.getString("locationId"), l.getString("name"))
        }
    }

    /** "Not here?": locations within [radiusMetres] of the round's position, nearest first. */
    suspend fun nearby(roundId: String, radiusMetres: Int = NOT_HERE_RADIUS): List<NearbyLocation> = nearbyOrCached(roundId, radiusMetres).first

    /** The same, and whether it came from the phone's copy (no signal, 3.2.25). */
    suspend fun nearbyOrCached(roundId: String, radiusMetres: Int = NOT_HERE_RADIUS): Pair<List<NearbyLocation>, Boolean> {
        val round = dao.getRound(roundId) ?: return emptyList<NearbyLocation>() to false
        val lat = round.fixLat ?: return emptyList<NearbyLocation>() to false
        val lon = round.fixLon ?: return emptyList<NearbyLocation>() to false
        return try {
            val json = call { token -> api.nearbyLocations(token, lat, lon, radiusMetres) }
            val list = json.getJSONArray("locations")
            (0 until list.length()).map { i ->
                val l = list.getJSONObject(i)
                NearbyLocation(l.getString("locationId"), l.getString("name"), l.getInt("distanceMetres"))
            } to false
        } catch (e: ApiException) {
            val known = cache?.places?.value.orEmpty()
            if (!e.isNetwork || known.isEmpty()) throw e
            LocationMatch.nearby(known, lat, lon, radiusMetres) to true
        }
    }

    suspend fun hasFix(roundId: String): Boolean = dao.getRound(roundId)?.fixLat != null

    /** The archer picked a location from the list. */
    suspend fun choose(roundId: String, location: NearbyLocation) {
        dao.setLocation(roundId, location.id, location.name, now())
        onChanged()
    }

    /**
     * Names a new location at the round's position. [check] (3.2.25): first asks whether it might be a location already
     * known (throws [SimilarExists]); false once the archer has said it's a new place ([skip]: the ones they turned down).
     * A name already used throws [NameTaken]. With no signal it's checked against the phone's copy, then kept on the round
     * and made when there's signal.
     */
    suspend fun name(roundId: String, name: String, isPrivate: Boolean = false, check: Boolean = true, skip: Set<String> = emptySet()) {
        val round = dao.getRound(roundId) ?: return
        val lat = round.fixLat ?: throw ApiException(400, "no_position", "This round has no GPS position.")
        create(roundId, PendingPlace(name.trim(), isPrivate, false, lat, round.fixLon!!, skip), check)
    }

    /** Names a new location at a point picked on the map, and gives it to the round. Same rules as [name]. */
    suspend fun nameAt(roundId: String, name: String, lat: Double, lon: Double, isPrivate: Boolean = false, check: Boolean = true,
        skip: Set<String> = emptySet()) =
        create(roundId, PendingPlace(name.trim(), isPrivate, false, lat, lon, skip), check)

    /**
     * 3.0: names a new location with no map pin (a shoot you can't place, e.g. a Nationals years ago) and gives it to
     * the round. Same rules as [name] (checked by name only).
     */
    suspend fun nameOnly(roundId: String, name: String, isPrivate: Boolean = false, check: Boolean = true, skip: Set<String> = emptySet()) =
        create(roundId, PendingPlace(name.trim(), isPrivate, true, null, null, skip), check)

    /** Makes [p] on the server and gives it to the round, or (no signal) keeps it on the round for later. */
    private suspend fun create(roundId: String, p: PendingPlace, check: Boolean) {
        val made = try {
            send(p, check)
        } catch (e: ApiException) {
            if (!e.isNetwork) throw e
            // No signal (3.2.25): check the phone's copy, then keep the name on the round until there's signal.
            if (check) {
                val local = LocationMatch.matches(p.name, p.latitude, p.longitude, cache?.places?.value.orEmpty(), p.skip)
                if (local.isNotEmpty()) throw SimilarExists(local, offline = true)
            }
            dao.setPendingLocation(roundId, PendingPlaceJson.encode(p.copy(matches = emptyList())), LocationState.PENDING_NAME.name)
            return
        }
        dao.setLocation(roundId, made.id, made.name, now())
        onChanged()
    }

    /**
     * Sends [p] to the server: [check] asks about similar locations first (ones in [PendingPlace.skip] don't count). The
     * made location, or [SimilarExists] / [NameTaken]; network problems are thrown as they are.
     */
    private suspend fun send(p: PendingPlace, check: Boolean): KnownPlace {
        val json = try {
            call { token ->
                if (p.noPin) api.createNamedLocation(token, p.name, p.isPrivate, checkSimilar = check)
                else api.createLocation(token, p.name, p.latitude!!, p.longitude!!, p.isPrivate, checkSimilar = check)
            }
        } catch (e: ApiException) {
            val existing = e.body?.optJSONObject("existing")
            if (e.code == "name_taken" && existing != null) {
                throw NameTaken(NearbyLocation(existing.getString("locationId"), existing.getString("name"), -1), e.message)
            }
            if (e.code == "similar_exists") {
                val all = e.body?.optJSONArray("matches")?.let { a -> (0 until a.length()).map { PendingPlaceJson.matchFrom(a.getJSONObject(it)) } }.orEmpty()
                val left = all.filter { it.id !in p.skip }
                // Only ones already turned down: go ahead.
                if (left.isEmpty()) return send(p, check = false)
                throw SimilarExists(left, offline = false)
            }
            throw e
        }
        val made = KnownPlace(json.getString("locationId"), json.getString("name"), p.latitude, p.longitude, p.isPrivate)
        cache?.add(made)
        return made
    }

    /**
     * 3.2.25: locations named with no signal are made now (on each sync). If the server knows locations that might be the
     * same one, the round waits for the archer to pick ([LocationState.CHECK_NAME]).
     */
    suspend fun createPending() {
        accounts.account.value?.token ?: return
        for (round in dao.pendingNames()) {
            val p = PendingPlaceJson.decode(round.pendingLocation) ?: continue
            try {
                val made = send(p, check = true)
                dao.setLocation(round.id, made.id, made.name, now())
                onChanged()
            } catch (e: SimilarExists) {
                dao.setPendingLocation(round.id, PendingPlaceJson.encode(p.copy(matches = e.matches)), LocationState.CHECK_NAME.name)
            } catch (e: NameTaken) {
                val m = PlaceMatch(e.existing.id, e.existing.name, false, null, listOf("same_name"))
                dao.setPendingLocation(round.id, PendingPlaceJson.encode(p.copy(matches = listOf(m))), LocationState.CHECK_NAME.name)
            } catch (e: ApiException) {
                if (e.isNetwork || e.status == 401 || e.status == 429 || e.status >= 500) throw e
                // Refused (e.g. the name isn't allowed): ask again.
                dao.setPendingLocation(round.id, PendingPlaceJson.encode(p.copy(matches = emptyList())), LocationState.NEEDS_NAME.name)
            }
        }
    }

    /**
     * 3.2.25: the archer's answer for a location named with no signal: [use] one of the suggestions, or null to keep their
     * own name (made now if there's signal; the suggestions aren't offered again).
     */
    suspend fun settlePending(roundId: String, use: PlaceMatch?) {
        val round = dao.getRound(roundId) ?: return
        val p = PendingPlaceJson.decode(round.pendingLocation) ?: return
        if (use != null) {
            dao.setLocation(roundId, use.id, use.name, now())
            onChanged()
            return
        }
        val kept = p.copy(skip = p.skip + p.matches.map { it.id }, matches = emptyList())
        dao.setPendingLocation(roundId, PendingPlaceJson.encode(kept), LocationState.PENDING_NAME.name)
        runCatching { createPending() }
    }

    class NameTaken(val existing: NearbyLocation, message: String) : Exception(message)

    /** 3.2.25: locations that might be the one being named; [offline]: from the phone's copy (no signal). */
    class SimilarExists(val matches: List<PlaceMatch>, val offline: Boolean) : Exception("This might be a location that's already known.")

    private suspend fun <T> call(block: suspend (String) -> T): T {
        val token = accounts.account.value?.token
            ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to set shoot locations.")
        try {
            return block(token)
        } catch (e: ApiException) {
            if (e.status == 401) accounts.clear()
            throw e
        }
    }

    private fun now(): Long = Instant.now(clock).toEpochMilli()

    /** One position: a recent last-known one if there is, else a fresh fix (up to [FIX_TIMEOUT_MS]). */
    @SuppressLint("MissingPermission")   // checked by hasPermission() before every call
    private suspend fun currentFix(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) return null

        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < RECENT_MS && it.accuracy <= GOOD_ACCURACY_M }
            .maxByOrNull { it.time }
        if (recent != null) return recent

        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, providers.first(), signal, executor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        }
    }

    private companion object {
        const val NOT_HERE_RADIUS = 10_000
        const val RECENT_MS = 2 * 60 * 1000L
        const val GOOD_ACCURACY_M = 200f
        const val FIX_TIMEOUT_MS = 30_000L
        val executor = Executors.newSingleThreadExecutor()
    }
}
