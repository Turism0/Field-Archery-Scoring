package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.fnurble.fieldarchery.domain.AnimalResult.Missed
import uk.co.fnurble.fieldarchery.domain.AnimalResult.Pending
import uk.co.fnurble.fieldarchery.domain.AnimalResult.Scored
import uk.co.fnurble.fieldarchery.domain.Hit.INNER_KILL
import uk.co.fnurble.fieldarchery.domain.Hit.KILL
import uk.co.fnurble.fieldarchery.domain.Hit.WOUND

class ScoringTest {

    // ---- Values ----

    @Test
    fun scoringValues() {
        assertEquals(24, BigGameScoring.points(1, INNER_KILL))
        assertEquals(20, BigGameScoring.points(1, KILL))
        assertEquals(16, BigGameScoring.points(1, WOUND))
        assertEquals(14, BigGameScoring.points(2, KILL))
        assertEquals(10, BigGameScoring.points(2, WOUND))
        assertEquals(8, BigGameScoring.points(3, KILL))
        assertEquals(4, BigGameScoring.points(3, WOUND))
    }

    @Test
    fun innerKillOnlyOnFirstArrow() {
        assertThrows(IllegalArgumentException::class.java) { Scored(2, INNER_KILL) }
        assertThrows(IllegalArgumentException::class.java) { Scored(3, INNER_KILL) }
        assertThrows(IllegalArgumentException::class.java) { Scored(4, KILL) }
    }

    // ---- Standard ----

    @Test
    fun standardTarget() {
        val t = TargetScore(1)
        assertFalse(t.isComplete)
        assertEquals(0, t.totalPoints)
        val scored = t.withAnimalResult(0, Scored(2, WOUND))
        assertEquals(10, scored.totalPoints)
        assertTrue(scored.isComplete)
        val missed = t.withAnimalResult(0, Missed)
        assertEquals(0, missed.totalPoints)
        assertTrue(missed.isComplete)
        assertEquals(24, t.maxPoints)
    }

    // ---- Predator / prey ----

    @Test
    fun predatorPreyMaxIs48() {
        val t = TargetScore(1).withType(TargetType.PREDATOR_PREY)
            .withAnimalResult(0, Scored(1, INNER_KILL))
            .withAnimalResult(1, Scored(1, INNER_KILL))
        assertEquals(48, t.totalPoints)
        assertEquals(48, t.maxPoints)
        assertTrue(t.isComplete)
    }

    @Test
    fun preyGetsRemainingArrowsScoredPerAnimal() {
        var t = TargetScore(1).withType(TargetType.PREDATOR_PREY)
        assertEquals(3, t.arrowsAvailable(0))
        assertEquals(0, t.arrowsAvailable(1)) // predator not shot yet

        t = t.withAnimalResult(0, Scored(1, KILL))
        assertEquals(2, t.arrowsAvailable(1))
        t = t.withAnimalResult(1, Scored(2, KILL)) // 2nd arrow at prey = 14
        assertEquals(34, t.totalPoints)

        t = t.withAnimalResult(0, Scored(2, WOUND)) // predator took 2 -> prey only had 1 -> prey reset
        assertEquals(Pending, t.animals[1])
        assertEquals(1, t.arrowsAvailable(1))
        t = t.withAnimalResult(1, Scored(1, WOUND))
        assertEquals(10 + 16, t.totalPoints)
    }

    @Test
    fun preyCannotExceedRemainingArrows() {
        val t = TargetScore(1).withType(TargetType.PREDATOR_PREY).withAnimalResult(0, Scored(2, KILL))
        assertThrows(IllegalArgumentException::class.java) { t.withAnimalResult(1, Scored(2, KILL)) }
    }

    @Test
    fun predatorMissedOrThirdArrowCompletesTarget() {
        val missed = TargetScore(1).withType(TargetType.PREDATOR_PREY).withAnimalResult(0, Missed)
        assertTrue(missed.isComplete)
        assertEquals(0, missed.totalPoints)

        val third = TargetScore(1).withType(TargetType.PREDATOR_PREY).withAnimalResult(0, Scored(3, KILL))
        assertTrue(third.isComplete)
        assertEquals(8, third.totalPoints)

        val first = TargetScore(1).withType(TargetType.PREDATOR_PREY).withAnimalResult(0, Scored(1, KILL))
        assertFalse(first.isComplete) // prey still to shoot
    }

    @Test
    fun preyCannotBeScoredBeforePredator() {
        val t = TargetScore(1).withType(TargetType.PREDATOR_PREY)
        assertThrows(IllegalArgumentException::class.java) { t.withAnimalResult(1, Scored(1, KILL)) }
    }

    // ---- Fan ----

    @Test
    fun fanScoresEachAnimalNormally() {
        var t = TargetScore(1).withType(TargetType.FAN, fanAnimals = 3)
        assertEquals(72, t.maxPoints)
        t = t.withAnimalResult(0, Scored(1, INNER_KILL))
            .withAnimalResult(1, Scored(3, KILL))
            .withAnimalResult(2, Missed)
        assertEquals(24 + 8, t.totalPoints)
        assertTrue(t.isComplete)
    }

    @Test
    fun fanLimits() {
        assertThrows(IllegalArgumentException::class.java) { TargetScore(1).withType(TargetType.FAN, 1) }
        assertThrows(IllegalArgumentException::class.java) { TargetScore(1).withType(TargetType.FAN, 7) }
    }

    // ---- Changing type mid-round ----

    @Test
    fun changingTypeKeepsFirstResult() {
        val std = TargetScore(5).withAnimalResult(0, Scored(1, KILL))
        val pp = std.withType(TargetType.PREDATOR_PREY)
        assertEquals(Scored(1, KILL), pp.animals[0])
        assertEquals(Pending, pp.animals[1])

        val fan = pp.withAnimalResult(1, Scored(2, WOUND)).withType(TargetType.FAN, 3)
        assertEquals(listOf(Scored(1, KILL), Scored(2, WOUND), Pending), fan.animals)

        // Back to predator/prey from a fan where animal 2 used more arrows than the prey could have
        val fan2 = TargetScore(5).withType(TargetType.FAN, 2)
            .withAnimalResult(0, Scored(2, KILL))
            .withAnimalResult(1, Scored(3, KILL))
        val pp2 = fan2.withType(TargetType.PREDATOR_PREY)
        assertEquals(Pending, pp2.animals[1])

        val back = fan.withType(TargetType.STANDARD)
        assertEquals(listOf<AnimalResult>(Scored(1, KILL)), back.animals)
    }

    // ---- Override ----

    @Test
    fun overrideReplacesCalculatedScore() {
        val t = TargetScore(1).withAnimalResult(0, Scored(1, KILL)).withOverride(30)
        assertEquals(30, t.totalPoints)
        assertEquals(20, t.calculatedPoints)
        assertTrue(t.isOverridden)
        assertTrue(TargetScore(2).withOverride(0).isComplete)
        assertEquals(20, t.withOverride(null).totalPoints)
        assertThrows(IllegalArgumentException::class.java) { TargetScore(1).withOverride(-1) }
    }

    // ---- Round ----

    @Test
    fun roundTotalsAndStats() {
        val round = Round(
            id = "r1",
            shootDate = java.time.LocalDate.of(2026, 10, 30),
            targetCount = 3,
            archerClass = ArcherClass.GENTS,
            ageGroup = AgeGroup.ADULT,
            bowStyle = BowStyle.LONGBOW,
            status = RoundStatus.IN_PROGRESS,
            targets = listOf(
                TargetScore(1).withAnimalResult(0, Scored(1, INNER_KILL)),
                TargetScore(2).withType(TargetType.PREDATOR_PREY)
                    .withAnimalResult(0, Scored(1, KILL)).withAnimalResult(1, Scored(1, WOUND)),
                TargetScore(3).withOverride(12),
            ),
        )
        assertEquals(24 + 36 + 12, round.totalPoints)
        assertTrue(round.isFullyScored)
        assertEquals("Gents Adult LB", round.classLine)
        val s = round.stats
        assertEquals(1, s.innerKills)
        assertEquals(1, s.kills)
        assertEquals(1, s.wounds)
        assertEquals(3, s.byArrow[1])
        assertEquals(1, s.overrides)
        assertEquals(3, s.targetsScored)
        // 72 points over 4 animals (standard 1 + predator/prey 2 + overridden standard 1)
        assertEquals(18.0, s.averagePerAnimal!!, 0.001)
    }

    private fun roundOf(vararg targets: TargetScore, count: Int = targets.size) = Round(
        id = "r",
        shootDate = java.time.LocalDate.of(2026, 10, 30),
        targetCount = count,
        archerClass = ArcherClass.GENTS,
        ageGroup = AgeGroup.ADULT,
        bowStyle = BowStyle.LONGBOW,
        status = RoundStatus.IN_PROGRESS,
        targets = targets.toList(),
    )

    @Test
    fun cannotSkipAheadOfUnscoredTarget() {
        val round = roundOf(
            TargetScore(1).withAnimalResult(0, Scored(2, KILL)),
            TargetScore(2),
            TargetScore(3),
        )
        assertEquals(2, round.firstUnscoredTargetNo)
        assertTrue(round.canGoTo(1))
        assertTrue(round.canGoTo(2))
        assertFalse(round.canGoTo(3))
        assertFalse(round.canGoTo(0))
        assertFalse(round.canGoTo(4))
    }

    @Test
    fun partlyScoredPredatorPreyBlocksNextTarget() {
        val round = roundOf(
            TargetScore(1).withType(TargetType.PREDATOR_PREY).withAnimalResult(0, Scored(1, KILL)),
            TargetScore(2),
        )
        assertEquals(1, round.firstUnscoredTargetNo)
        assertFalse(round.canGoTo(2))
    }

    @Test
    fun runningAverageIsPerAnimalOnScoredTargets() {
        val round = roundOf(
            TargetScore(1).withAnimalResult(0, Scored(1, KILL)),
            TargetScore(2).withAnimalResult(0, Missed),
            TargetScore(3),
            count = 3,
        )
        val s = round.stats
        assertEquals(2, s.targetsScored)
        assertEquals(10.0, s.averagePerAnimal!!, 0.001)
        assertEquals(1, s.misses)
        assertEquals(null, roundOf(TargetScore(1)).stats.averagePerAnimal)
    }

    @Test
    fun predatorPreyAndFanCountEachAnimalInAverage() {
        // Standard 20, predator/prey 20 + 10 (prey's 2nd arrow), 4-animal fan 24+20+0+0
        val round = roundOf(
            TargetScore(1).withAnimalResult(0, Scored(1, KILL)),
            TargetScore(2).withType(TargetType.PREDATOR_PREY)
                .withAnimalResult(0, Scored(1, KILL)).withAnimalResult(1, Scored(2, WOUND)),
            TargetScore(3).withType(TargetType.FAN, 4)
                .withAnimalResult(0, Scored(1, INNER_KILL)).withAnimalResult(1, Scored(1, KILL))
                .withAnimalResult(2, Missed).withAnimalResult(3, Missed),
        )
        val s = round.stats
        assertEquals(94, s.totalPoints)
        assertEquals(94.0 / 7, s.averagePerAnimal!!, 0.001)
        assertEquals(7, s.animalsScored)
        assertEquals(94.0 / (7 * 24), s.shareOfMax!!, 0.001)
        assertEquals(4.0 / 7, s.firstArrowRate!!, 0.001) // std, predator, fan 1, fan 2
        assertEquals(4.0 / 7, s.killRate!!, 0.001)       // IK + kills; prey was a wound
        assertEquals(2.0 / 7, s.missRate!!, 0.001)
    }

    @Test
    fun preyWithNoArrowsLeftCountsAsAnAnimalScoringZero() {
        val round = roundOf(
            TargetScore(1).withType(TargetType.PREDATOR_PREY).withAnimalResult(0, Scored(3, KILL)),
        )
        assertTrue(round.target(1).isComplete)
        assertEquals(4.0, round.stats.averagePerAnimal!!, 0.001)
    }
}
