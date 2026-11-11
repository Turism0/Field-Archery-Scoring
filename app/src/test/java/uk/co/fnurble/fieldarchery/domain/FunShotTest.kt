package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fun shots (3.1, Pete): a Big Game target with unusual scoring, typed in. In the round total, not in any stats. */
class FunShotTest {
    private fun kill(n: Int, hit: Hit = Hit.KILL) = TargetScore(n).withAnimalResult(0, AnimalResult.Scored(1, hit))
    private fun funShot(n: Int, points: Int?) = TargetScore(n).withType(TargetType.FUN).withOverride(points)
    private fun round(id: String, date: String, targets: List<TargetScore>) = Round(
        id, LocalDate.parse(date), targets.size, ArcherClass.GENTS, AgeGroup.ADULT, BowStyle.LONGBOW, RoundStatus.COMPLETED, targets,
    )

    @Test fun `a fun shot is scored once its score is typed in`() {
        val t = kill(1).withType(TargetType.FUN)
        assertTrue(t.animals.isEmpty()); assertNull(t.overridePoints)
        assertFalse(t.isComplete); assertEquals(0, t.totalPoints)
        assertTrue(funShot(1, 0).isComplete); assertEquals(45, funShot(1, 45).totalPoints); assertEquals(0, funShot(1, 45).maxPoints)
        assertNull(funShot(1, 45).withType(TargetType.STANDARD).overridePoints)
    }

    @Test fun `counts in the round total but not in the stats`() {
        val r = round("a", "2026-11-01", listOf(kill(1), kill(2), funShot(3, 45)))
        assertEquals(85, r.totalPoints); assertEquals(45, r.funPoints); assertEquals(40, r.statsPoints); assertEquals(2, r.statsTargetCount)
        val s = r.stats
        assertEquals(85, s.totalPoints); assertEquals(1, s.funShots); assertEquals(45, s.funPoints)
        assertEquals(2, s.targetsScored); assertEquals(20.0, s.averagePerAnimal!!, 0.001); assertEquals(0, s.overrides)
        assertEquals("3 targets · 1 fun shot", r.targetsLine)
    }

    @Test fun `is left out of the handicap and personal bests`() {
        val r = round("a", "2026-11-01", listOf(kill(1), kill(2), funShot(3, 45)))
        val e = r.handicapEntry()!!
        assertEquals(40, e.points); assertEquals(2, e.targetCount)
        val plain = round("b", "2026-10-01", listOf(kill(1), kill(2), kill(3, Hit.INNER_KILL)))
        val withFun = round("c", "2026-11-02", listOf(kill(1), kill(2, Hit.INNER_KILL), funShot(3, 45)))
        assertEquals(setOf(3 to 64, 2 to 44), PersonalStats.bests(listOf(plain, withFun)).map { it.key.targetCount to it.best.statsPoints }.toSet())
        // The PB goes on the best average without fun shots: 44 / 2 = 22 beats 64 / 3.
        assertEquals(setOf("c"), PersonalStats.personalBestIds(listOf(plain, withFun)))
    }

    @Test fun `goes through the QR check`() {
        assertEquals("X=30", LiveCheckQr.encodeTarget(funShot(4, 30)))
        assertEquals(funShot(4, 30), LiveCheckQr.decodeTarget(4, "X=30"))
        assertEquals(funShot(4, null), LiveCheckQr.decodeTarget(4, "X"))
    }
}
