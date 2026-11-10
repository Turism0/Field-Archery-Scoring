package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checking scores by QR (2.8): the same text and results as the web app's test/liveCheck.test.ts. */
class LiveCheckQrTest {
    private val group = "3f2a9c1b-0000-4000-8000-000000000001"
    private val me = "77d0e1aa-0000-4000-8000-000000000002"
    private val sue = "5e5e5e5e-0000-4000-8000-000000000003"
    private val dave = "9b1c22e0-0000-4000-8000-000000000004"
    private val bob = "b0b0b0b0-0000-4000-8000-000000000005"

    private fun std(n: Int, arrow: Int, hit: Hit) = TargetScore(n).withAnimalResult(0, AnimalResult.Scored(arrow, hit))
    private val pp = TargetScore(7, TargetType.PREDATOR_PREY, listOf(AnimalResult.Scored(1, Hit.KILL), AnimalResult.Missed))
    private val fan = TargetScore(9, TargetType.FAN, listOf(AnimalResult.Scored(2, Hit.WOUND), AnimalResult.Scored(1, Hit.INNER_KILL), AnimalResult.Pending))
    private val wood = TargetScore(8, TargetType.WOODSMAN, emptyList(), arrows = listOf(WoodsmanArrow.WOUND, WoodsmanArrow.KILL))
    private val over = TargetScore(10, overridePoints = 12)

    @Test
    fun encodesTheAgreedText() {
        val text = LiveCheckQr.encode(group, me, listOf(dave to std(6, 1, Hit.KILL), dave to pp, dave to wood, dave to fan, dave to over))
        assertEquals(
            "fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.S1K;7.9b1c22e0.P1K,M;8.9b1c22e0.WWK;9.9b1c22e0.F2W,1I,-;10.9b1c22e0.S-=12",
            text,
        )
    }

    @Test
    fun roundTrips() {
        val list = listOf(dave to std(6, 3, Hit.WOUND), dave to pp, bob to wood, bob to fan, dave to over)
        val p = LiveCheckQr.decode(LiveCheckQr.encode(group, me, list))!!
        assertEquals("3f2a9c1b", p.group); assertEquals("77d0e1aa", p.scorer)
        val back = p.items.associate { (it.targetNo to it.card) to it.target }
        for ((card, t) in list) assertEquals(t, back[t.targetNo to LiveCheckQr.short(card)])
    }

    @Test
    fun rejectsOtherText() {
        assertNull(LiveCheckQr.decode("fieldarchery:join:ABC234"))
        assertNull(LiveCheckQr.decode("fieldarchery:check:1:short:77d0e1aa:"))
        assertNull(LiveCheckQr.decode("fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.S4K"))   // no 4th arrow
        assertNull(LiveCheckQr.decode("fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.X1K"))
        assertEquals(0, LiveCheckQr.decode("fieldarchery:check:1:3f2a9c1b:77d0e1aa:")!!.items.size)
    }

    @Test
    fun comparesMatchesDifferencesAndMissing() {
        val theirs = LiveCheckQr.decode(LiveCheckQr.encode(group, sue, listOf(
            dave to std(6, 1, Hit.KILL), dave to std(7, 1, Hit.WOUND), bob to std(6, 1, Hit.INNER_KILL),
            dave to TargetScore(8),   // not scored yet on their phone
        )))!!
        val mine = mapOf(
            (6 to dave) to std(6, 1, Hit.KILL), (7 to dave) to std(7, 1, Hit.KILL),
            (6 to bob) to std(6, 1, Hit.INNER_KILL), (8 to dave) to std(8, 2, Hit.KILL), (9 to dave) to std(9, 1, Hit.KILL),
        )
        val r = LiveCheckQr.compare(mine, theirs)
        assertEquals(setOf(6 to dave, 6 to bob), r.matched)
        assertEquals(listOf(7 to dave), r.differences.map { it.targetNo to it.cardMemberId })
        assertEquals(20, r.differences[0].mine.totalPoints); assertEquals(16, r.differences[0].theirs.totalPoints)
        assertEquals(listOf(8 to dave, 9 to dave), r.notInTheirs)
    }

    @Test
    fun samePointsDifferentHitsDiffer() {
        // A fan of two, both 20: kill with arrow 1 and a miss, or a wound with arrow 1 and a wound with arrow 3.
        val a = TargetScore(5, TargetType.FAN, listOf(AnimalResult.Scored(1, Hit.KILL), AnimalResult.Missed))
        val b = TargetScore(5, TargetType.FAN, listOf(AnimalResult.Scored(1, Hit.WOUND), AnimalResult.Scored(3, Hit.WOUND)))
        assertEquals(a.totalPoints, b.totalPoints)
        val theirs = LiveCheckQr.decode(LiveCheckQr.encode(group, sue, listOf(dave to b)))!!
        assertEquals(1, LiveCheckQr.compare(mapOf((5 to dave) to a), theirs).differences.size)
    }

    @Test
    fun problems() {
        val scorers = mapOf(dave to listOf(me, sue), bob to listOf(me, sue))
        fun p(text: String) = LiveCheckQr.problem(LiveCheckQr.decode(text)!!, group, me, listOf(dave, bob)) { scorers[it].orEmpty() }
        assertNull(p(LiveCheckQr.encode(group, sue, emptyList())))
        assertTrue(p(LiveCheckQr.encode("ffffffff-0000", sue, emptyList()))!!.contains("different group"))
        assertTrue(p(LiveCheckQr.encode(group, me, emptyList()))!!.contains("own QR"))
        assertTrue(p(LiveCheckQr.encode(group, bob, emptyList()))!!.contains("doesn't score"))
    }
}
