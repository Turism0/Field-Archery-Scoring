package uk.co.fieldarcheryscoring.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.fieldarcheryscoring.domain.ScoringDisplay

/**
 * Scoring screen options, on this phone only: which figures to show for your card and for other archers' cards
 * (3.2.20, Pete; 3.2.18 had a single "show remaining and max possible" switch, carried over). Changed from the scoring
 * screen's ⋮ menu → Display options.
 */
class ScoringOptionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scoring_options", Context.MODE_PRIVATE)
    private val _display = MutableStateFlow(
        ScoringDisplay.decode(prefs.getString(K_DISPLAY, null), prefs.getBoolean(K_SHOW_REMAINING, false)),
    )
    val display: StateFlow<ScoringDisplay> = _display.asStateFlow()

    fun setDisplay(d: ScoringDisplay) {
        _display.value = d
        prefs.edit().putString(K_DISPLAY, d.encode()).remove(K_SHOW_REMAINING).apply()
    }

    private companion object {
        const val K_DISPLAY = "display"
        /** 3.2.18's single switch (read once, then replaced by [K_DISPLAY]). */
        const val K_SHOW_REMAINING = "show_remaining"
    }
}
