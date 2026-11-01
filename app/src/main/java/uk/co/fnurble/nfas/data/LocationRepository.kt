package uk.co.fnurble.nfas.data

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
import uk.co.fnurble.nfas.data.local.RoundDao
import uk.co.fnurble.nfas.data.remote.ApiException
import uk.co.fnurble.nfas.data.remote.NfasApi
import uk.co.fnurble.nfas.domain.NearbyLocation
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
 */
class LocationRepository(
    private val context: Context,
    private val dao: RoundDao,
    private val api: NfasApi,
    private val accounts: AccountStore,
    /** Called after a change that may need uploading. */
    private val onChanged: () -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
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
     * Looks up every round whose position hasn't been matched yet. Needs an account and signal;
     * network problems leave them waiting for next time. Called after a fix and on every sync.
     */
    suspend fun lookUpPending() = lookupLock.withLock {
        val token = accounts.account.value?.token ?: return@withLock
        for (round in dao.pendingLocationLookups()) {
            val json = api.nearbyLocations(token, round.fixLat!!, round.fixLon!!)
            val nearest = json.getJSONArray("locations").optJSONObject(0)
            if (nearest != null) {
                dao.setLocation(round.id, nearest.getString("locationId"), nearest.getString("name"), now())
                onChanged()
            } else {
                dao.markNeedsName(round.id)
            }
        }
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
    suspend fun nearby(roundId: String, radiusMetres: Int = NOT_HERE_RADIUS): List<NearbyLocation> {
        val round = dao.getRound(roundId) ?: return emptyList()
        val lat = round.fixLat ?: return emptyList()
        val lon = round.fixLon ?: return emptyList()
        val json = call { token -> api.nearbyLocations(token, lat, lon, radiusMetres) }
        val list = json.getJSONArray("locations")
        return (0 until list.length()).map { i ->
            val l = list.getJSONObject(i)
            NearbyLocation(l.getString("locationId"), l.getString("name"), l.getInt("distanceMetres"))
        }
    }

    suspend fun hasFix(roundId: String): Boolean = dao.getRound(roundId)?.fixLat != null

    /** The archer picked a location from the list. */
    suspend fun choose(roundId: String, location: NearbyLocation) {
        dao.setLocation(roundId, location.id, location.name, now())
        onChanged()
    }

    /**
     * Names a new location at the round's position. If that name is already taken, throws
     * [NameTaken] with the existing location so the archer can use it instead.
     */
    suspend fun name(roundId: String, name: String, isPrivate: Boolean = false) {
        val round = dao.getRound(roundId) ?: return
        val lat = round.fixLat ?: throw ApiException(400, "no_position", "This round has no GPS position.")
        val lon = round.fixLon!!
        val json = try {
            call { token -> api.createLocation(token, name.trim(), lat, lon, isPrivate) }
        } catch (e: ApiException) {
            val existing = e.body?.optJSONObject("existing")
            if (e.code == "name_taken" && existing != null) {
                throw NameTaken(NearbyLocation(existing.getString("locationId"), existing.getString("name"), -1), e.message)
            }
            throw e
        }
        dao.setLocation(roundId, json.getString("locationId"), json.getString("name"), now())
        onChanged()
    }

    class NameTaken(val existing: NearbyLocation, message: String) : Exception(message)

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
