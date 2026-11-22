package uk.co.fieldarcheryscoring.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import uk.co.fieldarcheryscoring.domain.ArcherNames

/**
 * Names you've given other archers (3.2.23, Pete): account id → name, on this phone only, never sent anywhere. Put away
 * with the account's other things at sign-out and brought back at sign-in (see [DeviceData]).
 */
class ArcherNamesStore(context: Context) {
    private val prefs = context.getSharedPreferences("archer_names", Context.MODE_PRIVATE)
    private val _names = MutableStateFlow(decode(prefs.getString(KEY, null)))
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Gives [archerId] the name [name]; blank removes it. */
    fun setName(archerId: String, name: String?) = save(ArcherNames.withName(_names.value, archerId, name))

    /** The names as text, to put away with an account's things at sign-out ([restore] brings them back). */
    fun current(): String? = prefs.getString(KEY, null)

    fun restore(text: String) {
        _names.value = decode(text)
        prefs.edit().putString(KEY, text).apply()
    }

    fun clear() {
        _names.value = emptyMap()
        prefs.edit().clear().apply()
    }

    private fun save(names: Map<String, String>) {
        _names.value = names
        prefs.edit().putString(KEY, JSONObject(names as Map<*, *>).toString()).apply()
    }

    private companion object {
        const val KEY = "names"

        fun decode(text: String?): Map<String, String> = runCatching {
            val j = JSONObject(text ?: return emptyMap())
            j.keys().asSequence().mapNotNull { k -> ArcherNames.clean(j.optString(k))?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }
}
