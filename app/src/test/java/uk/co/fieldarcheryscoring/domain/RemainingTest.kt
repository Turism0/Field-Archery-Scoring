package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Remaining and max possible while scoring (3.2.18). The same cases as the web's remaining.test.ts. */
class RemainingTest {
    private val k1 = AnimalResult.Scored(1, Hit.KILL)
    private fun big(type: TargetType, fan: Int = TargetScore.MIN_FAN) = TargetScore(1).withType(type, fan)
    private fun woods(vararg a: WoodsmanArrow) = TargetScore(1, TargetType.WOODSMAN, arrows = a.toList())

    @Test fun `Big Game targets`() {
        assertEquals(24, TargetScore(1).remainingPoints)
        assertEquals(0, TargetScore(1).withAnimalResult(0, AnimalResult.Scored(2, Hit.KILL)).remainingPoints)
        assertEquals(48, big(TargetType.FAN, 3).withAnimalResult(0, k1).remainingPoints)
        assertEquals(48, big(TargetType.PREDATOR_PREY).remainingPoints)
        assertEquals(24, big(TargetType.PREDATOR_PREY).withAnimalResult(0, k1).remainingPoints)
        assertEquals(0, big(TargetType.PREDATOR_PREY).withAnimalResult(0, AnimalResult.Scored(3, Hit.WOUND)).remainingPoints)
        assertEquals(0, big(TargetType.PREDATOR_PREY).withAnimalResult(0, AnimalResult.Missed).remainingPoints)
        assertEquals(0, TargetScore(1).withOverride(10).remainingPoints)
        assertEquals(0, big(TargetType.FUN).remainingPoints)
    }

    @Test fun `Woodsman - a kill on the next arrow`() {
        assertEquals(30, woods().remainingPoints)
        assertEquals(20, woods(WoodsmanArrow.WOUND).remainingPoints)
        assertEquals(10, woods(WoodsmanArrow.MISS, WoodsmanArrow.WOUND).remainingPoints)
        assertEquals(0, woods(WoodsmanArrow.WOUND, WoodsmanArrow.KILL).remainingPoints)
        assertEquals(0, woods(WoodsmanArrow.MISS, WoodsmanArrow.MISS, WoodsmanArrow.MISS).remainingPoints)
    }

    @Test fun `a round - unscored targets count as one animal`() {
        val targets = listOf(
            TargetScore(1).withAnimalResult(0, k1),
            TargetScore(2).withType(TargetType.FAN, 2).withAnimalResult(0, AnimalResult.Scored(2, Hit.WOUND)),
        )
        val r = Round("r", LocalDate.parse("2026-11-18"), 4, ArcherClass.GENTS, AgeGroup.ADULT, BowStyle.LONGBOW, RoundStatus.IN_PROGRESS, targets)
        // Total 30; remaining 24 (fan) + 24 + 24 (targets 3 and 4).
        assertEquals(72, r.remainingPoints)
        assertEquals(102, r.maxPossiblePoints)
    }
}
