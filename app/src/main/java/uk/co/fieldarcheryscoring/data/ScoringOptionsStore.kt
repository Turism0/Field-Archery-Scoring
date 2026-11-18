package uk.co.fieldarcheryscoring.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scoring screen options, on this phone only (3.2.18): whether to show the remaining points and the max possible under
 * the total (a tester via Pete). Off until switched on in the scoring screen's menu.
 */
class ScoringOptionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scoring_options", Context.MODE_PRIVATE)
    private val _showRemaining = MutableStateFlow(prefs.getBoolean(K_SHOW_REMAINING, false))
    val showRemaining: StateFlow<Boolean> = _showRemaining.asStateFlow()

    fun setShowRemaining(on: Boolean) {
        _showRemaining.value = on
        prefs.edit().putBoolean(K_SHOW_REMAINING, on).apply()
    }

    private companion object {
        const val K_SHOW_REMAINING = "show_remaining"
    }
}
