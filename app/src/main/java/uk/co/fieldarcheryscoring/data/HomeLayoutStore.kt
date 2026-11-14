package uk.co.fieldarcheryscoring.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.fieldarcheryscoring.domain.HomeLayout

/** How Home's tiles are arranged (2.4.01): on this phone only, never sent anywhere. */
class HomeLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
    private val _layout = MutableStateFlow(HomeLayout.decode(prefs.getString(KEY, null)))
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    fun save(layout: HomeLayout) {
        val l = layout.normalised()
        _layout.value = l
        prefs.edit().putString(KEY, l.encode()).apply()
    }

    private companion object {
        const val KEY = "layout"
    }
}
