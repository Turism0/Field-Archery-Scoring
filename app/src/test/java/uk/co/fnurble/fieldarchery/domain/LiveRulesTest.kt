package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The phone's copy of the server's group rules (2.3): they must agree with GroupRules.cs. */
class LiveRulesTest {
    private fun m(id: String, kind: String, order: Int) = LiveRules.Member(id, kind, order)

    private val host = m("H", LiveRules.HOST, 0)
    private val a = m("A", LiveRules.JOINED, 1)
    private val b = m("B", LiveRules.JOINED, 2)
    private val c = m("C", LiveRules.JOINED, 3)
    private val guest = m("G", LiveRules.GUEST, 4)

    private fun scored(n: Int, hit: Hit = Hit.KILL, arrow: Int = 1) =
        TargetScore(n).withAnimalResult(0, AnimalResult.Scored(arrow, hit))

    private fun entry(n: Int, card: String, scorer: String, t: TargetScore = scored(n), at: Long = 1) =
        LiveRules.Entry(n, card, scorer, t, Instant.ofEpochMilli(at))

    @Test
    fun singleHostScoresEveryone() {
        val (s, p) = LiveRules.defaultScorers(LiveRules.SINGLE, listOf(host, a, guest))
        assertTrue(p.isEmpty())
        assertEquals(mapOf("H" to listOf("H"), "A" to listOf("H"), "G" to listOf("H")), s)
        assertTrue(LiveRules.validate(LiveRules.SINGLE, listOf(host, a, guest), s).isEmpty())
    }

    @Test
    fun doubleNeedsASecondPhone() {
        assertEquals(1, LiveRules.defaultScorers(LiveRules.DOUBLE, listOf(host, guest)).second.size)
        val (s, _) = LiveRules.defaultScorers(LiveRules.DOUBLE, listOf(host, a, b, guest))
        assertTrue(s.values.all { it == listOf("H", "A") })
        assertTrue(LiveRules.validate(LiveRules.DOUBLE, listOf(host, a, b, guest), s).isEmpty())
    }

    @Test
    fun multiNextTwoRoundTheGroupAndEvenLoad() {
        val all = listOf(host, a, b, c, guest)
        val (s, p) = LiveRules.defaultScorers(LiveRules.MULTI, all)
        assertTrue(p.isEmpty())
        assertEquals(listOf("A", "B"), s["H"])
        assertEquals(listOf("B", "C"), s["A"])
        assertEquals(listOf("C", "H"), s["B"])
        assertEquals(listOf("H", "A"), s["C"])
        // The guest goes to the two with fewest (all have 2; then scoring order).
        assertEquals(listOf("H", "A"), s["G"])
        assertTrue(LiveRules.validate(LiveRules.MULTI, all, s).isEmpty())
        assertEquals(1, LiveRules.defaultScorers(LiveRules.MULTI, listOf(host, a, guest)).second.size)
    }

    @Test
    fun selfScoringAndValidateCatchesProblems() {
        val all = listOf(host, a, guest)
        val (s, _) = LiveRules.defaultScorers(LiveRules.SELF, all)
        assertEquals(mapOf("H" to listOf("H"), "A" to listOf("A"), "G" to listOf("H")), s)
        assertTrue(LiveRules.validate(LiveRules.SELF, all, s).isEmpty())
        assertTrue(LiveRules.validate(LiveRules.SELF, all, s + ("A" to listOf("H"))).isNotEmpty())
        assertTrue(LiveRules.validate(LiveRules.SELF, all, s - "G").isNotEmpty())   // every card needs a scorer
        assertTrue(LiveRules.validate(LiveRules.SINGLE, all, mapOf("H" to listOf("G"), "A" to listOf("G"), "G" to listOf("G"))).isNotEmpty())
    }

    @Test
    fun cellsWaitMatchAndMismatch() {
        val scorers = mapOf("A" to listOf("H", "B"))
        assertEquals(LiveRules.WAITING, LiveRules.cells(listOf(entry(1, "A", "H")), scorers)[1 to "A"])
        assertEquals(LiveRules.MATCHED, LiveRules.cells(listOf(entry(1, "A", "H"), entry(1, "A", "B")), scorers)[1 to "A"])
        // A different hit.
        val other = entry(1, "A", "B", TargetScore(1).withAnimalResult(0, AnimalResult.Scored(1, Hit.WOUND)))
        val same = scored(1).totalPoints == other.target.totalPoints
        assertEquals(LiveRules.MISMATCH, LiveRules.cells(listOf(entry(1, "A", "H"), other), scorers)[1 to "A"])
        if (same) assertTrue(LiveRules.matchKey(scored(1)) != LiveRules.matchKey(other.target))
        // Someone not scoring the card is ignored.
        assertEquals(LiveRules.WAITING, LiveRules.cells(listOf(entry(1, "A", "H"), entry(1, "A", "C")), scorers)[1 to "A"])
        // Before scorers are set, one entry counts.
        assertEquals(LiveRules.MATCHED, LiveRules.cells(listOf(entry(1, "A", "H")), emptyMap())[1 to "A"])
    }

    @Test
    fun newestEntryWins() {
        val old = entry(1, "A", "H", scored(1, Hit.WOUND), at = 10)
        val new = entry(1, "A", "H", scored(1, Hit.KILL), at = 20)
        val (m1, c1) = LiveRules.mergeEntries(mapOf(old.key to old), listOf(new))
        assertEquals(new, m1[old.key])
        assertEquals(1, c1.size)
        val (m2, c2) = LiveRules.mergeEntries(m1, listOf(old, new))
        assertEquals(new, m2[old.key])
        assertTrue(c2.isEmpty())
    }

    @Test
    fun statusOnlyMovesForward() {
        assertEquals(LiveRules.STARTED, LiveRules.laterStatus(LiveRules.STARTED, LiveRules.OPEN))
        assertEquals(LiveRules.CLOSED, LiveRules.laterStatus(LiveRules.STARTED, LiveRules.CLOSED))
        assertEquals(LiveRules.CLOSED, LiveRules.laterStatus(LiveRules.CLOSED, LiveRules.OPEN))
    }

    @Test
    fun manualCheckDueAfterFiveUncomparedTargets() {
        val scorers = mapOf("A" to listOf("H", "B"), "H" to listOf("A", "B"))
        val mine = (1..4).map { entry(it, "A", "H") }
        assertTrue(LiveRules.dueManualCheck("H", mine, scorers, emptySet()).isEmpty())
        val five = mine + entry(5, "A", "H")
        assertEquals(listOf(1, 2, 3, 4, 5), LiveRules.dueManualCheck("H", five, scorers, emptySet()))
        // Matched targets don't count.
        val withOther = five + entry(2, "A", "B")
        assertTrue(LiveRules.dueManualCheck("H", withOther, scorers, emptySet()).isEmpty())
        // Checked by hand: the next five.
        val checked = (1..5).map { it to "A" }.toSet()
        val ten = five + (6..10).map { entry(it, "A", "H") }
        assertEquals(listOf(6, 7, 8, 9, 10), LiveRules.dueManualCheck("H", ten, scorers, checked))
        // Single scorer cards are never due.
        assertTrue(LiveRules.dueManualCheck("H", five, mapOf("A" to listOf("H")), emptySet()).isEmpty())
    }
}
