package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Stats analysis and weather (3.2.17). The same cases as the web app's test/analysis.test.ts. */
class AnalysisTest {
    private fun s(arrow: Int, hit: Hit): AnimalResult = AnimalResult.Scored(arrow, hit)
    private val m: AnimalResult = AnimalResult.Missed
    private fun std(n: Int, a: AnimalResult) = TargetScore(n, TargetType.STANDARD, listOf(a))

    private fun bg(id: String, date: String, targets: List<TargetScore>, location: String? = null, start: Int? = null) = Round(
        id = id, shootDate = LocalDate.parse(date), targetCount = targets.size, archerClass = ArcherClass.GENTS,
        ageGroup = AgeGroup.ADULT, bowStyle = BowStyle.LONGBOW, status = RoundStatus.COMPLETED, targets = targets,
        location = RoundLocation(LocationStatus.SET, location), startTarget = start,
    )

    private fun flat(id: String, date: String, per: Int, n: Int = 3, location: String? = null) =
        bg(id, date, (1..n).map { newTarget(RoundType.BIG_GAME, it).withOverride(per) }, location)

    private fun near(expected: Double, actual: Double?) = assertEquals(expected, actual!!, 0.0001)

    @Test fun `counts which arrow scored, and blanks`() {
        val r = bg("a", "2026-05-01", listOf(
            std(1, s(1, Hit.KILL)), std(2, s(2, Hit.WOUND)), std(3, m), std(4, s(3, Hit.KILL)),
            TargetScore(5, TargetType.PREDATOR_PREY, listOf(s(1, Hit.INNER_KILL), s(2, Hit.KILL))),
            newTarget(RoundType.BIG_GAME, 6).withOverride(20),
            TargetScore(7, TargetType.FUN, emptyList(), overridePoints = 50),
        ))
        assertEquals(ArrowCounts(2, 2, 1, 1, 6), Analysis.arrowCounts(listOf(r)))
        assertEquals(HitCounts(1, 3, 1, 1, 6), Analysis.hitCounts(listOf(r)))
    }

    @Test fun `Woodsman counts per target`() {
        val w = Round(
            id = "w", shootDate = LocalDate.parse("2026-05-01"), targetCount = 3, archerClass = ArcherClass.GENTS, ageGroup = AgeGroup.ADULT,
            bowStyle = BowStyle.LONGBOW, status = RoundStatus.COMPLETED, type = RoundType.WOODSMAN,
            targets = listOf("K", "WWK", "WMW").mapIndexed { i, a -> TargetScore(i + 1, TargetType.WOODSMAN, emptyList(), arrows = WoodsmanScoring.decode(a)) },
        )
        assertEquals(ArrowCounts(1, 0, 1, 1, 3), Analysis.arrowCounts(listOf(w)))
        assertEquals(HitCounts(0, 2, 1, 0, 3), Analysis.hitCounts(listOf(w)))
    }

    @Test fun `hits over time by year across years, by month within one`() {
        val rs = listOf(bg("a", "2025-06-01", listOf(std(1, s(1, Hit.KILL)))), bg("b", "2026-04-01", listOf(std(1, m))),
            bg("c", "2026-05-01", listOf(std(1, s(1, Hit.WOUND)))))
        assertEquals(listOf("2025" to 1, "2026" to 2), Analysis.hitsOverTime(rs).map { it.label to it.hits.total })
        assertEquals(listOf("Apr 2026", "May 2026"), Analysis.hitsOverTime(rs.drop(1)).map { it.label })
    }

    @Test fun `average per animal by target type`() {
        val r = bg("a", "2026-05-01", listOf(std(1, s(1, Hit.KILL)), std(2, s(1, Hit.INNER_KILL)),
            TargetScore(3, TargetType.PREDATOR_PREY, listOf(s(1, Hit.KILL), s(2, Hit.WOUND))),
            TargetScore(4, TargetType.FAN, listOf(s(1, Hit.WOUND), m, s(1, Hit.KILL)))))
        assertEquals(listOf(
            TypeAverage(TargetType.STANDARD, 2, 2, 22.0),
            TypeAverage(TargetType.PREDATOR_PREY, 1, 2, 15.0),
            TypeAverage(TargetType.FAN, 1, 3, 12.0),
        ), Analysis.byTargetType(listOf(r)))
    }

    @Test fun `consistency, streaks and form`() {
        val rs = listOf(10, 12, 14, 16, 18, 17, 19).mapIndexed { i, v -> flat("r$i", "2026-0${i + 1}-01", v) }
        val c = Analysis.consistency(rs)!!
        assertEquals(7, c.rounds); near(15.142857, c.average); near(3.287784, c.spread)
        assertEquals(Streak(c.average, 4, 4), Analysis.streak(rs))
        val f = Analysis.form(rs, RoundType.BIG_GAME)!!
        near(16.8, f.recent); near(11.0, f.before); assertEquals(FormDirection.UP, f.direction)
        assertNull(Analysis.form(rs.take(5), RoundType.BIG_GAME))
        assertNull(Analysis.consistency(rs.take(2)))
    }

    @Test fun `start, middle and finish follow the shooting order from the starting target`() {
        val targets = listOf(24, 24, 20, 20, 16, 16).mapIndexed { i, p -> newTarget(RoundType.BIG_GAME, i + 1).withOverride(p) }
        val r = bg("a", "2026-05-01", targets, start = 5)
        assertEquals(listOf(5, 6, 1, 2, 3, 4), Analysis.shootingOrder(r))
        assertEquals(Thirds(16.0, 24.0, 20.0), Analysis.thirds(listOf(r)))
        assertEquals(Thirds(24.0, 20.0, 16.0), Analysis.thirds(listOf(r.copy(startTarget = null))))
    }

    @Test fun `by location, most-shot first`() {
        val rs = listOf(flat("a", "2026-01-01", 18, location = "Sherwood"), flat("b", "2026-02-01", 20, location = "sherwood "),
            flat("c", "2026-03-01", 22, location = "Bowland"), flat("d", "2026-04-01", 10))
        assertEquals(
            listOf(listOf("Sherwood", 2, 19.0, 20.0, "b"), listOf("Bowland", 1, 22.0, 22.0, "c")),
            Analysis.byLocation(rs).map { listOf(it.name, it.rounds, it.average, it.best, it.bestRound.id) },
        )
    }

    @Test fun `weather lines and splits`() {
        val wet = Weather(6.4, 30.0, 52.0, 3.2, 63)
        val dry = Weather(15.0, 8.0, null, 0.0, 1)
        assertEquals("6°C · Rain, 3.2 mm · wind 19 mph, gusts 32 mph", WeatherRules.line(wet))
        assertEquals("15°C · Partly cloudy · wind 5 mph", WeatherRules.line(dry))
        assertEquals(listOf(true, false, true, false),
            listOf(WeatherRules.isWet(wet), WeatherRules.isWet(dry), WeatherRules.isWindy(wet), WeatherRules.isWindy(dry)))
        assertEquals("Sunny", WeatherRules.condition(0)); assertEquals("Thunder", WeatherRules.condition(95))
        val rs = listOf(flat("a", "2026-01-01", 16), flat("b", "2026-02-01", 20), flat("c", "2026-03-01", 22), flat("d", "2026-04-01", 10))
        val w = mapOf("a" to wet, "b" to dry, "c" to dry)
        val splits = Analysis.weatherSplits(rs) { w[it.id] }
        assertEquals(WeatherSplit("Rain", "Wet", "Dry", SplitSide(1, 16.0), SplitSide(2, 21.0)), splits[0])
        assertEquals(1, splits[2].yes.rounds); assertEquals(2, splits[2].no.rounds)
    }

    @Test fun `compared with others needs 20 rounds`() {
        assertEquals("Better than 70% of Gents Adult Longbow rounds", Analysis.comparedText(0.704, 25, "Gents Adult Longbow"))
        assertNull(Analysis.comparedText(0.7, 19, "x"))
    }
}
