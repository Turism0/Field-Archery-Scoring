package uk.co.fieldarcheryscoring.data

import org.json.JSONArray
import org.json.JSONObject
import uk.co.fieldarcheryscoring.data.remote.ApiException
import uk.co.fieldarcheryscoring.data.remote.FieldArcheryApi
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.Round

/** One kind of round to compare: round type, bow style, class and age group (the server's codes). */
data class CompareKey(val roundType: String, val bowStyle: String, val archerClass: String, val ageGroup: String) {
    companion object {
        fun of(r: Round) = CompareKey(r.type.name, r.bowStyle.code, if (r.archerClass == ArcherClass.LADIES) "L" else "G", r.ageGroup.name)
    }
}

/** How many of everyone else's rounds of that kind [betterThan] (0..1) beat, or null with too few. */
data class CompareResult(val key: CompareKey, val rounds: Int, val archers: Int, val betterThan: Double?)

/** Stats → Analysis → Compared with others (3.2.17): from the server, needs a sign-in and signal. */
class CompareRepository(private val api: FieldArcheryApi, private val accounts: AccountStore) {
    suspend fun compare(groups: Map<CompareKey, Double>): List<CompareResult> {
        val token = accounts.account.value?.token ?: throw ApiException(401, "not_signed_in", "Sign in to compare with other archers.")
        val body = JSONArray()
        groups.forEach { (k, avg) ->
            body.put(JSONObject().put("roundType", k.roundType).put("bowStyle", k.bowStyle).put("archerClass", k.archerClass)
                .put("ageGroup", k.ageGroup).put("average", avg))
        }
        val res = api.compareStats(token, body).optJSONArray("groups") ?: JSONArray()
        return (0 until res.length()).map { i ->
            val g = res.getJSONObject(i)
            CompareResult(
                CompareKey(g.getString("roundType"), g.getString("bowStyle"), g.getString("archerClass"), g.getString("ageGroup")),
                g.optInt("rounds"), g.optInt("archers"), if (g.isNull("betterThan")) null else g.optDouble("betterThan"),
            )
        }
    }
}
