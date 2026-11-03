package uk.co.fnurble.nfas.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import uk.co.fnurble.nfas.data.NetworkStatus
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ApiConfig {
    /** The Field Archery Scoring API on Odin, through Nginx Proxy Manager. */
    const val BASE_URL = "https://fieldarchery.fnurble.co.uk"
}

/**
 * A failed API call. [message] is written to show to the archer.
 * [status] 0 means the server couldn't be reached (no signal, server down).
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    /** The error reply, for extra details (e.g. the existing location on name_taken). */
    val body: JSONObject? = null,
) : Exception(message) {
    val isNetwork: Boolean get() = status == 0
}

data class UsernameCheck(val username: String, val exists: Boolean)

data class AuthResult(val archerId: String, val username: String, val token: String, val expiresUtc: String)

/**
 * Small client for the API using the platform's HttpURLConnection and org.json,
 * so the app needs no extra libraries. All calls run on the IO dispatcher.
 */
class NfasApi(
    private val baseUrl: String = ApiConfig.BASE_URL,
    /** Told whether each call reached the server, for the home screen notices and error wording. */
    private val network: NetworkStatus? = null,
) {

    /** Checks the server is up (no sign-in needed). Throws [ApiException] if it isn't. */
    suspend fun health(): JSONObject = request("GET", "/health", body = null)


    suspend fun checkUsername(username: String): UsernameCheck {
        val json = request("POST", "/api/auth/check", JSONObject().put("username", username))
        return UsernameCheck(json.getString("username"), json.getBoolean("exists"))
    }

    suspend fun register(username: String, password: String, deviceName: String): AuthResult =
        request("POST", "/api/auth/register", credentials(username, password, deviceName)).toAuthResult()

    suspend fun login(username: String, password: String, deviceName: String): AuthResult =
        request("POST", "/api/auth/login", credentials(username, password, deviceName)).toAuthResult()

    suspend fun logout(token: String) {
        request("POST", "/api/auth/logout", body = null, token = token)
    }

    /** Uploads a whole round. Safe to repeat: the server keeps the copy with the latest clientUpdatedUtc. */
    suspend fun uploadRound(token: String, roundId: String, round: JSONObject) {
        request("PUT", "/api/rounds/$roundId", round, token)
    }

    /** Marks a round deleted on the server (fine if it was never uploaded). */
    suspend fun deleteRound(token: String, roundId: String) {
        request("DELETE", "/api/rounds/$roundId", body = null, token = token)
    }

    /**
     * One page of this archer's rounds changed after the cursor (oldest change first, deletions included):
     * { rounds: [ { roundId, ..., isDeleted, serverUpdatedUtc, targets: [...] } ], more: true|false }.
     */
    suspend fun changedRounds(token: String, since: String?, afterId: String?): JSONObject {
        val query = listOfNotNull(
            since?.let { "since=" + URLEncoder.encode(it, "UTF-8") },
            afterId?.let { "afterId=$it" },
            "limit=50",
        ).joinToString("&")
        return request("GET", "/api/rounds?$query", body = null, token = token)
    }

    /** { archerId, username, createdUtc, role, duplicateLocations, roundOffers } for the signed-in account. */
    suspend fun me(token: String): JSONObject = request("GET", "/api/me", body = null, token = token)

    // ---- Admin (the server checks the role on every call) ----

    /** Admins and Owner: { archers: [ { archerId, username, role, createdUtc, lastLoginUtc } ] } */
    suspend fun adminArchers(token: String): JSONObject = request("GET", "/api/admin/archers", body = null, token = token)

    /** Owner only: role "ADMIN" or "USER". */
    suspend fun setRole(token: String, archerId: String, role: String) {
        request("PUT", "/api/admin/archers/$archerId/role", JSONObject().put("role", role), token)
    }

    // ---- Shoot locations ----

    /** { locations: [ { locationId, name, latitude, longitude, isPrivate, myRounds, rounds } ] } for the map. */
    suspend fun locationMap(token: String): JSONObject = request("GET", "/api/locations/map", body = null, token = token)

    /** { location, myRounds, friends: [ { archerId, username, rounds, bestTotal, averagePerAnimal, roundsList } ], bowStyles } */
    suspend fun locationPage(token: String, locationId: String): JSONObject =
        request("GET", "/api/locations/$locationId/page", body = null, token = token)

    /** Admins and Owner: { locations: [ { locationId, name, latitude, longitude, createdUtc, createdBy, rounds } ] } */
    suspend fun adminLocations(token: String): JSONObject = request("GET", "/api/admin/locations", body = null, token = token)

    /** Admins and Owner: 409 name_taken if another live location has that name. */
    suspend fun renameLocation(token: String, locationId: String, name: String, isPrivate: Boolean? = null): JSONObject =
        request(
            "PUT", "/api/admin/locations/$locationId",
            JSONObject().put("name", name).apply { isPrivate?.let { put("isPrivate", it) } }, token,
        )

    /** Admins and Owner: { pairs: [ { a: {...}, b: {...}, distanceMetres, reasons: [...] } ] } */
    suspend fun duplicateLocations(token: String): JSONObject =
        request("GET", "/api/admin/locations/duplicates", body = null, token = token)

    /** Admins and Owner: the two are different places; stop flagging them. */
    suspend fun approveLocationPair(token: String, aId: String, bId: String) {
        request("POST", "/api/admin/locations/duplicates/approve", JSONObject().put("aId", aId).put("bId", bId), token)
    }

    /** Admins and Owner: { locationId, name, roundsMoved }; the duplicate's rounds move to [intoId]. */
    suspend fun mergeLocation(token: String, locationId: String, intoId: String): JSONObject =
        request("POST", "/api/admin/locations/$locationId/merge", JSONObject().put("intoId", intoId), token)

    /** { locations: [ { locationId, name, distanceMetres } ], catchmentMetres }, nearest first. Position isn't stored. */
    suspend fun nearbyLocations(token: String, lat: Double, lon: Double, radiusMetres: Int? = null): JSONObject {
        val query = listOfNotNull(
            "lat=" + String.format(java.util.Locale.ROOT, "%.6f", lat),
            "lon=" + String.format(java.util.Locale.ROOT, "%.6f", lon),
            radiusMetres?.let { "radius=$it" },
        ).joinToString("&")
        return request("GET", "/api/locations/nearby?$query", body = null, token = token)
    }

    /** 201 { locationId, name }; 409 name_taken with { existing: { locationId, name } } in [ApiException.body]. */
    suspend fun createLocation(token: String, name: String, lat: Double, lon: Double, isPrivate: Boolean = false): JSONObject =
        request(
            "POST", "/api/locations",
            JSONObject().put("name", name).put("latitude", lat).put("longitude", lon).put("isPrivate", isPrivate), token,
        )

    /** { locations: [ { requestedId, locationId, name } ] }: current id and name for ids this phone holds. */
    suspend fun resolveLocations(token: String, ids: List<String>): JSONObject =
        request("POST", "/api/locations/resolve", JSONObject().put("ids", org.json.JSONArray(ids)), token)

    // ---- Friends ----

    /** { friends: [...], incoming: [...], outgoing: [...] } */
    suspend fun friends(token: String): JSONObject = request("GET", "/api/friends", body = null, token = token)

    /** { result: "requested" | "accepted", message } */
    suspend fun sendFriendRequest(token: String, username: String): JSONObject =
        request("POST", "/api/friends/requests", JSONObject().put("username", username), token)

    suspend fun acceptFriend(token: String, archerId: String) {
        request("POST", "/api/friends/$archerId/accept", body = null, token = token)
    }

    /** Decline, cancel or unfriend. */
    suspend fun removeFriend(token: String, archerId: String) {
        request("DELETE", "/api/friends/$archerId", body = null, token = token)
    }

    /** { rounds: [ { roundId, shootDate, targetCount, archerClass, ageGroup, bowStyle, totalPoints } ] } */
    suspend fun friendRounds(token: String, archerId: String): JSONObject =
        request("GET", "/api/friends/$archerId/rounds", body = null, token = token)

    /** One of a friend's finished rounds, same shape as the download list. */
    suspend fun friendRound(token: String, archerId: String, roundId: String): JSONObject =
        request("GET", "/api/friends/$archerId/rounds/$roundId", body = null, token = token)

    // ---- Rounds scored for a friend (requests they accept or decline) ----

    /** Sends (or corrects) a finished round scored for a friend. 409 round_exists once they've accepted it. */
    suspend fun offerRound(token: String, roundId: String, forArcherId: String, groupId: String?, round: JSONObject) {
        val body = JSONObject().put("forArcherId", forArcherId).put("groupId", groupId ?: JSONObject.NULL).put("round", round)
        request("PUT", "/api/offers/$roundId", body, token)
    }

    /** Takes back a request not yet answered (404 if it was already answered or never sent). */
    suspend fun withdrawOffer(token: String, roundId: String) {
        request("DELETE", "/api/offers/$roundId", body = null, token = token)
    }

    /** { received: [...], sent: [...] } */
    suspend fun offers(token: String): JSONObject = request("GET", "/api/offers", body = null, token = token)

    /** { offer: {...}, round: {...} } */
    suspend fun offer(token: String, roundId: String): JSONObject = request("GET", "/api/offers/$roundId", body = null, token = token)

    suspend fun acceptOffer(token: String, roundId: String) {
        request("POST", "/api/offers/$roundId/accept", body = null, token = token)
    }

    suspend fun declineOffer(token: String, roundId: String) {
        request("POST", "/api/offers/$roundId/decline", body = null, token = token)
    }

    private fun credentials(username: String, password: String, deviceName: String) =
        JSONObject().put("username", username).put("password", password).put("deviceName", deviceName)

    private fun JSONObject.toAuthResult() = AuthResult(
        archerId = getString("archerId"),
        username = getString("username"),
        token = getString("token"),
        expiresUtc = getString("expiresUtc"),
    )

    private suspend fun request(method: String, path: String, body: JSONObject?, token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = try {
                URL(baseUrl + path).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw networkError()
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                // Nginx Proxy Manager answers 502/504 when the API on Odin is down; the API says 503 if SQL is.
                if (status in 502..504) network?.serverFailed() else network?.serverAnswered()

                if (status in 200..299) {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } else {
                    val err = try { JSONObject(text) } catch (e: JSONException) { null }
                    throw ApiException(
                        status = status,
                        code = err?.optString("error").orEmpty().ifBlank { "http_$status" },
                        message = err?.optString("message").orEmpty().ifBlank { defaultMessage(status) },
                        body = err,
                    )
                }
            } catch (e: IOException) {
                throw networkError()
            } catch (e: JSONException) {
                throw ApiException(-1, "bad_response", "The server sent an unexpected reply. Try again later.")
            } finally {
                conn.disconnect()
            }
        }

    /** Couldn't connect: either this device is offline, or it's online and the server isn't answering. */
    private fun networkError(): ApiException {
        network?.serverFailed()
        return if (network?.online?.value == false) {
            ApiException(0, "offline", "Your device is offline. It'll catch up when you have signal.")
        } else {
            ApiException(0, "network", "The server isn't available at the moment. Try again later.")
        }
    }

    private fun defaultMessage(status: Int) = when (status) {
        401 -> "Your sign-in has expired. Please sign in again."
        429 -> "Too many attempts. Wait a minute and try again."
        in 502..504 -> "The server isn't available at the moment. Try again later."
        in 500..599 -> "The server had a problem. Try again later."
        else -> "Something went wrong ($status). Try again."
    }
}
