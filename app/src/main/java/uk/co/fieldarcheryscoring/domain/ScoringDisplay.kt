package uk.co.fieldarcheryscoring.domain

/**
 * What the scoring screen shows about a card (3.2.20, Pete): knowing how you're doing can affect your shooting, so
 * each figure can be switched off. Shown at the top of the scoring screen, in the ☰ scorecard sheet and (the total)
 * in the group names strip. Same as the web's domain/scoringDisplay.ts.
 */
data class StatsDisplay(
    val total: Boolean = true,
    val average: Boolean = true,
    /** Inner kill / kill / wound / miss counts (and which arrow scored, in the scorecard sheet). */
    val hits: Boolean = true,
    val remaining: Boolean = false,
    val maxPossible: Boolean = false,
) {
    fun encode(): String = listOf(total, average, hits, remaining, maxPossible).joinToString("") { if (it) "1" else "0" }

    companion object {
        fun decode(s: String?): StatsDisplay? {
            if (s == null || s.length != 5 || s.any { it != '0' && it != '1' }) return null
            val b = s.map { it == '1' }
            return StatsDisplay(b[0], b[1], b[2], b[3], b[4])
        }
    }
}

/** Your own card, and everyone else's in a group (a round on its own is always yours). */
data class ScoringDisplay(val mine: StatsDisplay = StatsDisplay(), val others: StatsDisplay = StatsDisplay()) {
    fun forCard(own: Boolean): StatsDisplay = if (own) mine else others

    /** "11100|11100" (total, average, hits, remaining, max possible). */
    fun encode(): String = "${mine.encode()}|${others.encode()}"

    companion object {
        /**
         * The saved choice; else the defaults, with 3.2.18's single "show remaining and max possible" switch carried
         * over to both cards when it was on.
         */
        fun decode(s: String?, oldShowRemaining: Boolean = false): ScoringDisplay {
            val parts = s?.split('|')
            val mine = parts?.getOrNull(0)?.let(StatsDisplay::decode)
            val others = parts?.getOrNull(1)?.let(StatsDisplay::decode)
            if (mine != null && others != null) return ScoringDisplay(mine, others)
            val d = StatsDisplay(remaining = oldShowRemaining, maxPossible = oldShowRemaining)
            return ScoringDisplay(d, d)
        }
    }
}
