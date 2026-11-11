package uk.co.fnurble.fieldarchery.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.fnurble.fieldarchery.domain.StatsFilter

/** The Stats screen's last filters (3.2): on this phone only, never sent anywhere. */
class StatsFilterStore(context: Context) {
    private val prefs = context.getSharedPreferences("stats_filter", Context.MODE_PRIVATE)
    private val _saved = MutableStateFlow(StatsFilter.decode(prefs.getString(KEY, null)))
    val saved: StateFlow<StatsFilter.Saved?> = _saved.asStateFlow()

    fun save(filter: StatsFilter) {
        val text = filter.encode()
        _saved.value = StatsFilter.decode(text)
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val KEY = "filter"
    }
}
