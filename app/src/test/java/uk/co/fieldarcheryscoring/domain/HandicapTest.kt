package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

class HandicapTest {
    private val model = HandicapModel(
        modelId = 1, dataThroughYear = 2026,
        factors = mapOf(
            "BOW:LB" to -1.36, "SLOPE:LB" to -0.25, "BOW3D:LB" to -0.07, "BOWSEX:LB" to -0.01,
            "CLASS:F-ADULT" to -0.40, "CLASS:M-JUNIOR" to -0.12,
        ),
    )
    private val ul = HandicapModel.Archer.REFERENCE
    private val lb = HandicapModel.Archer(BowStyle.LONGBOW, ArcherClass.GENTS, AgeGroup.ADULT)
    private val lbLady = HandicapModel.Archer(BowStyle.LONGBOW, ArcherClass.LADIES, AgeGroup.ADULT)

    private fun logit(p: Double) = ln(p / (1 - p))

    @Test
    fun unlimitedGentsIsUnchanged() {
        assertEquals(600.0, model.convert(600.0, 960.0, ul, 2026), 1e-9)
    }

    @Test
    fun longbowByTheFormula() {
        // 2026: BOW + SLOPE × 1.2 + half the 3D term.
        val off = -1.36 - 0.25 * 1.2 + 0.5 * -0.07
        val expected = 960 / (1 + exp(-(logit(600.0 / 960) - off)))
        assertEquals(expected, model.convert(600.0, 960.0, lb, 2026), 1e-9)
        assertTrue(expected > 800)
    }

    @Test
    fun ladiesAddClassAndBowTerms() {
        val off = -1.36 - 0.25 * 1.2 + 0.5 * -0.07 - 0.40 - 0.01
        val expected = 960 / (1 + exp(-(logit(500.0 / 960) - off)))
        assertEquals(expected, model.convert(500.0, 960.0, lbLady, 2026), 1e-9)
    }

    @Test
    fun yearsAfterTheDataUseTheLastYear() {
        assertEquals(model.convert(600.0, 960.0, lb, 2026), model.convert(600.0, 960.0, lb, 2031), 1e-9)
        assertTrue(model.convert(600.0, 960.0, lb, 2005) < model.convert(600.0, 960.0, lb, 2026))
    }

    @Test
    fun conversionRoundTrips() {
        val asLongbow = model.convert(800.0, 960.0, ul, 2020, to = lb)
        assertEquals(800.0, model.convert(asLongbow, 960.0, lb, 2020, to = ul), 1e-6)
        assertTrue(asLongbow < 800)
    }

    @Test
    fun unknownFactorsAreZero() {
        val bb = HandicapModel.Archer(BowStyle.BAREBOW, ArcherClass.GENTS, AgeGroup.ADULT)
        assertEquals(600.0, model.convert(600.0, 960.0, bb, 2026), 1e-9)
    }

    // ---- Multipliers (2.9) ----

    private val withBase = model.copy(factors = model.factors + ("BASE:ALL" to 1.2))

    @Test
    fun multiplierIsTheMaximumOverATypicalScore() {
        // Unlimited Gents: typical share 1 / (1 + e^-1.2) = 0.7685 → 1.30.
        assertEquals(1.30, withBase.multiplier(ul)!!, 1e-9)
        val share = 1 / (1 + exp(-(1.2 - 1.36 - 0.25 * 1.2 + 0.5 * -0.07)))
        assertEquals(Math.round(100 / share) / 100.0, withBase.multiplier(lb)!!, 1e-9)
        assertTrue(withBase.multiplier(lb)!! > withBase.multiplier(ul)!!)
        assertTrue(withBase.multiplier(lbLady)!! > withBase.multiplier(lb)!!)
    }

    @Test
    fun aTypicalScoreTimesItsMultiplierIsAboutTheMaximum() {
        val typical = (withBase.typicalShare(lb)!! * 960).toInt()
        assertEquals(960.0, HandicapModel.applyMultiplier(typical, withBase.multiplier(lb)!!).toDouble(), 6.0)
    }

    @Test
    fun noMultipliersFromAnOlderServer() {
        assertNull(model.multiplier(lb))
    }

    // ---- Personal handicap (2.9) ----

    private fun e(day: Int, points: Int, targets: Int = 40, bow: BowStyle = BowStyle.LONGBOW, order: Long = 0) =
        PersonalHandicap.Entry(LocalDate.of(2026, 1, 1).plusDays(day.toLong()), order, bow, points, targets)

    @Test
    fun howManyRoundsCount() {
        assertEquals(listOf(0, 0, 3, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8), listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 13, 14, 15, 17, 18, 20).map { PersonalHandicap.counting(it) })
    }

    @Test
    fun noHandicapUntilThreeRounds() {
        assertNull(PersonalHandicap.of(listOf(e(1, 600), e(2, 620)), BowStyle.LONGBOW))
        val h = PersonalHandicap.of(listOf(e(1, 600), e(2, 620), e(3, 640)), BowStyle.LONGBOW)!!
        assertEquals(960 - 620, h.handicap)
        assertEquals(3, h.counted.size)
    }

    @Test
    fun bestEightOfTheLastTwenty() {
        // 25 rounds: the oldest 5 (all 900) are too old; of the latest 20 (500 … 690), the best 8 are 620 … 690.
        val old = (1..5).map { e(it, 900) }
        val recent = (0 until 20).map { e(10 + it, 500 + it * 10) }
        val h = PersonalHandicap.of(old + recent, BowStyle.LONGBOW)!!
        assertEquals(20, h.considered)
        assertEquals(8, h.counted.size)
        assertEquals((620..690 step 10).average(), h.usual, 1e-9)
        assertEquals(960 - 655, h.handicap)
    }

    @Test
    fun roundsAreScaledTo40Targets() {
        // 36 targets: 540 is 600 off 40.
        val h = PersonalHandicap.of(listOf(e(1, 540, 36), e(2, 600), e(3, 300, 20)), BowStyle.LONGBOW)!!
        assertEquals(600.0, h.usual, 1e-9)
        assertEquals(360, h.handicap)
    }

    @Test
    fun onePerBowStyle() {
        val list = listOf(e(1, 600), e(2, 600), e(3, 600), e(4, 800, bow = BowStyle.UNLIMITED), e(5, 800, bow = BowStyle.UNLIMITED))
        val all = PersonalHandicap.all(list)
        assertEquals(listOf(BowStyle.LONGBOW), all.map { it.bowStyle })
        assertEquals(360, all[0].handicap)
    }

    @Test
    fun handicapBeforeARound() {
        val list = listOf(e(1, 600), e(2, 600), e(3, 600), e(4, 900), e(4, 300, order = 5))
        val third = list[2]
        assertNull(PersonalHandicap.of(list, BowStyle.LONGBOW, before = third))
        // Before the second round on day 4: day 1–3 and the first on day 4 → best 3 of 4 = 900, 600, 600.
        assertEquals(960 - 700, PersonalHandicap.of(list, BowStyle.LONGBOW, before = list[4])!!.handicap)
    }

    @Test
    fun netScoreScalesTheHandicapToTheTargets() {
        assertEquals(960, PersonalHandicap.net(600, 40, 360))
        assertEquals(540 + 324, PersonalHandicap.net(540, 36, 360))
        assertEquals(324, PersonalHandicap.allowance(360, 36))
        assertEquals(864, PersonalHandicap.parFor(36))
    }
}
