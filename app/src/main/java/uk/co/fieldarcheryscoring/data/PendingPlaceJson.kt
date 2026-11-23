package uk.co.fieldarcheryscoring.data

import org.json.JSONArray
import org.json.JSONObject
import uk.co.fieldarcheryscoring.domain.PendingPlace
import uk.co.fieldarcheryscoring.domain.PlaceMatch

/** 3.2.25: [PendingPlace] as stored in round.pending_location (phone only). */
object PendingPlaceJson {
    fun encode(p: PendingPlace): String = JSONObject()
        .put("name", p.name).put("private", p.isPrivate).put("noPin", p.noPin)
        .put("lat", p.latitude ?: JSONObject.NULL).put("lon", p.longitude ?: JSONObject.NULL)
        .put("skip", JSONArray(p.skip.toList()))
        .put("matches", JSONArray().apply { p.matches.forEach { put(matchJson(it)) } })
        .toString()

    fun decode(text: String?): PendingPlace? = runCatching {
        val j = JSONObject(text ?: return null)
        PendingPlace(
            name = j.getString("name"), isPrivate = j.optBoolean("private"), noPin = j.optBoolean("noPin"),
            latitude = if (j.isNull("lat")) null else j.optDouble("lat"), longitude = if (j.isNull("lon")) null else j.optDouble("lon"),
            skip = j.optJSONArray("skip")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            matches = j.optJSONArray("matches")?.let { a -> (0 until a.length()).map { matchFrom(a.getJSONObject(it)) } }.orEmpty(),
        )
    }.getOrNull()

    private fun matchJson(m: PlaceMatch) = JSONObject().put("locationId", m.id).put("name", m.name).put("isPrivate", m.isPrivate)
        .put("distanceMetres", m.distanceMetres ?: JSONObject.NULL).put("reasons", JSONArray(m.reasons))

    /** One match, from the server's 409 similar_exists or as stored here. */
    fun matchFrom(o: JSONObject) = PlaceMatch(
        o.getString("locationId"), o.getString("name"), o.optBoolean("isPrivate"),
        if (!o.has("distanceMetres") || o.isNull("distanceMetres")) null else o.getInt("distanceMetres"),
        o.optJSONArray("reasons")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
    )
}
