package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Score history order (3.2.01): the same cases as the web app's stats.test.ts. */
class HistorySortTest {
    // Newest first, as the database gives them: id to average (null = nothing scored).
    private val rounds = listOf("d" to null, "b" to 22.0, "c" to 18.0, "a" to 15.0)
    private fun ids(s: HistorySort) = s.sort(rounds) { it.second }.map { it.first }

    @Test fun `by date, either way`() {
        assertEquals(listOf("d", "b", "c", "a"), ids(HistorySort.DATE_DESC))
        assertEquals(listOf("a", "c", "b", "d"), ids(HistorySort.DATE_ASC))
    }

    @Test fun `by average, either way, nothing scored last`() {
        assertEquals(listOf("b", "c", "a", "d"), ids(HistorySort.AVG_DESC))
        assertEquals(listOf("a", "c", "b", "d"), ids(HistorySort.AVG_ASC))
    }

    @Test fun `a tie goes newest first`() {
        val tie = listOf("new" to 20.0, "old" to 20.0)
        assertEquals(listOf("new", "old"), HistorySort.AVG_DESC.sort(tie) { it.second }.map { it.first })
        assertEquals(listOf("new", "old"), HistorySort.AVG_ASC.sort(tie) { it.second }.map { it.first })
    }
}
