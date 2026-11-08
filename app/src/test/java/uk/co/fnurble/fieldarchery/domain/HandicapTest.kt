package uk.co.fnurble.fieldarchery.domain

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

    @Test
    fun handicapPointsAreTheSameForTheSamePerformance() {
        // A Longbow score and its Unlimited equivalent get the same handicap points.
        val asUl = model.convert(600.0, 960.0, lb, 2026)
        assertEquals(model.handicapPoints(600.0, 960.0, lb, 2026), model.handicapPoints(asUl, 960.0, ul, 2026), 1e-6)
        // Out of 1000, and higher for a better score.
        val hp = model.handicapPoints(600.0, 960.0, lb, 2026)
        assertTrue(hp > 0 && hp < 1000)
        assertTrue(model.handicapPoints(700.0, 960.0, lb, 2026) > hp)
    }

    @Test
    fun neutralIsTheMiddleOfAllBowsAndClasses() {
        val all = BowStyle.entries.flatMap { b -> ArcherClass.entries.flatMap { c -> AgeGroup.entries.map { a -> model.offset(b, c, a, 2026) } } }.sorted()
        val n = model.neutralOffset(2026)
        assertTrue(all.count { it < n } <= all.size / 2 && all.count { it > n } <= all.size / 2)
        // An archer exactly at the middle keeps their share: 50% → 500.
        val unchanged = 1000 / (1 + exp(-(logit(0.5) - 0 + n)))
        assertEquals(unchanged, model.handicapPoints(480.0, 960.0, ul, 2026), 1e-6)
    }

    @Test
    fun ratingAveragesTheLatestTen() {
        val rounds = (1..12).map { HandicapRound(LocalDate.of(2026, 1, it), points = 480 + it, maxPoints = 960, archer = lb) }
        val r = HandicapStats.rating(model, rounds)!!
        assertEquals(10, r.rounds)
        // Latest 10 = days 3..12.
        val expected = rounds.drop(2).map { model.handicapPoints(it.points.toDouble(), 960.0, lb, 2026) }.average()
        assertEquals(Math.round(expected).toInt(), r.rating)
        assertNull(HandicapStats.rating(model, rounds.take(2)))
    }
}
