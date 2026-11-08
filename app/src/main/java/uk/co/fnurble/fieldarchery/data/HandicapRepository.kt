package uk.co.fnurble.fieldarchery.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.domain.AccountRole
import uk.co.fnurble.fieldarchery.domain.HandicapModel
import java.time.Duration
import java.time.Instant

/** A championship as Admin → Handicap shows it. */
data class HandicapEvent(
    /** NATIONALS or 3DS. */
    val champs: String,
    val year: Int,
    /** RESULTS, PLACINGS (no scores) or NONE. */
    val status: String,
    val resultCount: Int,
    val targetsPerDay: Int?,
    /** RULE, AUTO, DOUBTFUL (the server job) or ADMIN (set by hand). */
    val targetsSource: String?,
    val targetsNote: String?,
    val targetsSetBy: String?,
    val checkedUtc: Instant?,
) {
    val name: String get() = (if (champs == "3DS") "3D Championships" else "Nationals") + " $year"
}

data class HandicapModelInfo(
    val modelId: Int,
    val fittedUtc: Instant?,
    /** ACTIVE, CANDIDATE (waiting) or RETIRED. */
    val status: String,
    val dataThroughYear: Int,
    val events: Int,
    val dayScores: Int,
    val archers: Int,
    val holdoutRmse: Double,
    val switcherBias: Double,
    val note: String?,
    val activatedBy: String?,
)

data class HandicapRun(
    val kind: String,
    val requestedUtc: Instant?,
    val requestedBy: String?,
    val finishedUtc: Instant?,
    val outcome: String?,
    val failed: Boolean,
)

data class HandicapOverview(val events: List<HandicapEvent>, val models: List<HandicapModelInfo>, val runs: List<HandicapRun>)

/**
 * The handicap model, downloaded from the server (`GET /api/handicap`, no sign-in) and kept on the phone so
 * equivalents and the calculator work offline. Refreshed at most once a day when there's signal. Also the
 * Admin → Handicap calls.
 */
class HandicapRepository(context: Context, private val api: FieldArcheryApi, private val accounts: AccountStore) {
    private val prefs = context.getSharedPreferences("handicap", Context.MODE_PRIVATE)
    private val _model = MutableStateFlow(prefs.getString(K_JSON, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() })

    /** The handicap model on this phone (null until it's been downloaded once). */
    val model: StateFlow<HandicapModel?> = _model.asStateFlow()

    /** Downloads the model if it's more than a day old (or never). Problems are ignored: it's tried again later. */
    suspend fun refreshIfStale() {
        val last = prefs.getLong(K_FETCHED, 0L)
        if (_model.value != null && Instant.ofEpochMilli(last).isAfter(Instant.now().minus(Duration.ofDays(1)))) return
        refresh()
    }

    /** Downloads the model now. Returns false if the server has none yet or can't be reached. */
    suspend fun refresh(): Boolean = try {
        val json = api.handicap()
        val m = parse(json)
        prefs.edit().putString(K_JSON, json.toString()).putLong(K_FETCHED, System.currentTimeMillis()).apply()
        _model.value = m
        true
    } catch (e: ApiException) {
        false
    }

    private fun parse(json: JSONObject): HandicapModel = HandicapModel(
        modelId = json.getInt("modelId"),
        dataThroughYear = json.getInt("dataThroughYear"),
        yearZero = json.optInt("yearZero", 2014),
        factors = json.getJSONArray("factors").objects().associate { f -> "${f.getString("kind")}:${f.getString("code")}" to f.getDouble("value") },
    )

    // ---- Admin → Handicap ----

    suspend fun overview(): HandicapOverview = call { token ->
        val json = api.adminHandicap(token)
        HandicapOverview(
            events = json.getJSONArray("events").objects().map { e ->
                HandicapEvent(
                    champs = e.getString("champs"),
                    year = e.getInt("year"),
                    status = e.getString("status"),
                    resultCount = e.optInt("resultCount"),
                    targetsPerDay = if (e.isNull("targetsPerDay")) null else e.getInt("targetsPerDay"),
                    targetsSource = e.stringOrNull("targetsSource"),
                    targetsNote = e.stringOrNull("targetsNote"),
                    targetsSetBy = e.stringOrNull("targetsSetBy"),
                    checkedUtc = e.instantOrNull("checkedUtc"),
                )
            },
            models = json.getJSONArray("models").objects().map { m ->
                HandicapModelInfo(
                    modelId = m.getInt("modelId"),
                    fittedUtc = m.instantOrNull("fittedUtc"),
                    status = m.getString("status"),
                    dataThroughYear = m.getInt("dataThroughYear"),
                    events = m.optInt("events"),
                    dayScores = m.optInt("dayScores"),
                    archers = m.optInt("archers"),
                    holdoutRmse = m.optDouble("holdoutRmse"),
                    switcherBias = m.optDouble("switcherBias"),
                    note = m.stringOrNull("note"),
                    activatedBy = m.stringOrNull("activatedBy"),
                )
            },
            runs = json.getJSONArray("runs").objects().map { r ->
                HandicapRun(
                    kind = r.getString("kind"),
                    requestedUtc = r.instantOrNull("requestedUtc"),
                    requestedBy = r.stringOrNull("requestedBy"),
                    finishedUtc = r.instantOrNull("finishedUtc"),
                    outcome = r.stringOrNull("outcome"),
                    failed = r.optBoolean("failed"),
                )
            },
        )
    }

    /** 36 or 40 by hand, or null to let the server job decide again. */
    suspend fun setTargets(champs: String, year: Int, targetsPerDay: Int?) = call { token ->
        api.setHandicapTargets(token, champs, year, targetsPerDay); Unit
    }

    /** Returns the server's message (the job reads nfas.net on its next weekly run). */
    suspend fun requestFetch(): String = call { token -> api.requestHandicapFetch(token).optString("message", "Requested.") }

    /** Owner only. Downloads the new model afterwards. */
    suspend fun activate(modelId: Int) {
        call { token -> api.activateHandicapModel(token, modelId) }
        refresh()
    }

    private suspend fun <T> call(block: suspend (String) -> T): T {
        val token = accounts.account.value?.token ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) first.")
        try {
            return block(token)
        } catch (e: ApiException) {
            when {
                e.status == 401 -> accounts.clear()
                e.code == "not_admin" -> accounts.setRole(AccountRole.USER)
                e.code == "not_owner" -> accounts.setRole(AccountRole.ADMIN)
            }
            throw e
        }
    }

    private companion object {
        const val K_JSON = "model_json"
        const val K_FETCHED = "fetched_at"
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
private fun JSONObject.instantOrNull(name: String): Instant? =
    if (!has(name) || isNull(name)) null else runCatching { Instant.parse(getString(name)) }.getOrNull()
