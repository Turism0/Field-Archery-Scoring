package uk.co.fnurble.nfas.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ApiConfig {
    /** The NFAS API on Odin, through Nginx Proxy Manager. */
    const val BASE_URL = "https://nfas.fnurble.co.uk"
}

/**
 * A failed API call. [message] is written to show to the archer.
 * [status] 0 means the server couldn't be reached (no signal, server down).
 */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message) {
    val isNetwork: Boolean get() = status == 0
}

data class UsernameCheck(val username: String, val exists: Boolean)

data class AuthResult(val archerId: String, val username: String, val token: String, val expiresUtc: String)

/**
 * Small client for the NFAS API using the platform's HttpURLConnection and org.json,
 * so the app needs no extra libraries. All calls run on the IO dispatcher.
 */
class NfasApi(private val baseUrl: String = ApiConfig.BASE_URL) {

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

                if (status in 200..299) {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } else {
                    val err = try { JSONObject(text) } catch (e: JSONException) { null }
                    throw ApiException(
                        status = status,
                        code = err?.optString("error").orEmpty().ifBlank { "http_$status" },
                        message = err?.optString("message").orEmpty().ifBlank { defaultMessage(status) },
                    )
                }
            } catch (e: IOException) {
                throw networkError()
            } catch (e: JSONException) {
                throw ApiException(-1, "bad_response", "The NFAS server sent an unexpected reply. Try again later.")
            } finally {
                conn.disconnect()
            }
        }

    private fun networkError() =
        ApiException(0, "network", "Couldn't reach the NFAS server. Check your signal and try again.")

    private fun defaultMessage(status: Int) = when (status) {
        401 -> "Your sign-in has expired. Please sign in again."
        429 -> "Too many attempts. Wait a minute and try again."
        in 500..599 -> "The NFAS server had a problem. Try again later."
        else -> "Something went wrong ($status). Try again."
    }
}
