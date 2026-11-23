package uk.co.fieldarcheryscoring.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.co.fieldarcheryscoring.domain.KnownPlace
import java.io.File

/**
 * The phone's copy of every shoot location you can use (3.2.25, Pete): public ones and your own private ones, with or
 * without a map pin. Refreshed on each sync (and whenever the list is fetched) so locations can be found and picked with
 * no signal. Cleared with the account's other things when the sign-in ends (private ones are that account's).
 */
class PlaceCache(context: Context) {
    private val file = File(context.filesDir, "known_places.json")
    private val _places = MutableStateFlow(load())
    val places: StateFlow<List<KnownPlace>> = _places.asStateFlow()

    /** When the copy was last refreshed (0 = never). */
    val refreshedAt: Long get() = if (file.exists()) file.lastModified() else 0L

    suspend fun replace(list: List<KnownPlace>) {
        _places.value = list
        withContext(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                list.forEach { p ->
                    arr.put(JSONObject().put("id", p.id).put("name", p.name).put("lat", p.latitude ?: JSONObject.NULL)
                        .put("lon", p.longitude ?: JSONObject.NULL).put("private", p.isPrivate))
                }
                file.writeText(arr.toString())
            }
        }
    }

    /** A location just made here: in the copy straight away (the next refresh brings the server's list anyway). */
    suspend fun add(p: KnownPlace) = replace(_places.value.filter { it.id != p.id } + p)

    fun clear() {
        _places.value = emptyList()
        runCatching { file.delete() }
    }

    private fun load(): List<KnownPlace> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KnownPlace(
                o.getString("id"), o.getString("name"),
                if (o.isNull("lat")) null else o.getDouble("lat"), if (o.isNull("lon")) null else o.getDouble("lon"),
                o.optBoolean("private"),
            )
        }
    }.getOrDefault(emptyList())
}
