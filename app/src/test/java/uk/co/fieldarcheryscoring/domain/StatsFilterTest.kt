package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Stats screen's filters (3.2): the same cases as the web app's statsFilter.test.ts. */
class StatsFilterTest {
    private val today = LocalDate.parse("2026-11-12")
    private fun d(s: String) = LocalDate.parse(s)
    private fun r(id: String, date: String, bow: BowStyle, type: RoundType = RoundType.BIG_GAME) = Round(
        id, d(date), 36, ArcherClass.GENTS, AgeGroup.ADULT, bow, RoundStatus.COMPLETED, emptyList(), type = type,
    )
    private val rolling1 = StatsFilter.Period(StatsFilter.PeriodKind.ROLLING, 1)

    @Test fun `rolling years run back from today`() {
        assertTrue(rolling1.contains(d("2025-11-13"), today))
        assertFalse(rolling1.contains(d("2025-11-12"), today))
        assertTrue(StatsFilter.Period(StatsFilter.PeriodKind.ROLLING, 3).contains(d("2023-12-01"), today))
    }

    @Test fun `calendar years include this year`() {
        val c1 = StatsFilter.Period(StatsFilter.PeriodKind.CALENDAR, 1)
        assertTrue(c1.contains(d("2026-01-01"), today)); assertFalse(c1.contains(d("2025-12-31"), today))
        assertTrue(StatsFilter.Period(StatsFilter.PeriodKind.CALENDAR, 2).contains(d("2025-01-01"), today))
        assertTrue(StatsFilter.Period(StatsFilter.PeriodKind.ALL).contains(d("2001-01-01"), today))
    }

    @Test fun labels() {
        assertEquals("Last 1 year (rolling)", rolling1.label(today))
        assertEquals("This year (2026)", StatsFilter.Period(StatsFilter.PeriodKind.CALENDAR, 1).label(today))
        assertEquals("Last 3 years (2024–2026)", StatsFilter.Period(StatsFilter.PeriodKind.CALENDAR, 3).label(today))
        assertEquals("All time", StatsFilter.Period(StatsFilter.PeriodKind.ALL).label(today))
    }

    @Test fun `first time - every type, the profile bow, the last year rolling`() {
        val rounds = listOf(
            r("a", "2026-01-01", BowStyle.COMPOUND_LIMITED), r("b", "2026-02-01", BowStyle.COMPOUND_LIMITED),
            r("c", "2015-04-12", BowStyle.AMERICAN_FLATBOW), r("w", "2026-03-01", BowStyle.LONGBOW, RoundType.WOODSMAN),
        )
        assertEquals(
            StatsFilter(setOf(RoundType.BIG_GAME, RoundType.WOODSMAN), setOf(BowStyle.AMERICAN_FLATBOW), rolling1),
            StatsFilter.resolve(null, rounds, BowStyle.AMERICAN_FLATBOW),
        )
        assertEquals(setOf(BowStyle.COMPOUND_LIMITED), StatsFilter.resolve(null, rounds, BowStyle.BAREBOW).bows)
        assertEquals(setOf(BowStyle.COMPOUND_LIMITED), StatsFilter.resolve(null, rounds, null).bows)
    }

    @Test fun `remembers the last choice, less anything with no rounds`() {
        val rounds = listOf(r("a", "2026-01-01", BowStyle.COMPOUND_LIMITED), r("c", "2015-04-12", BowStyle.AMERICAN_FLATBOW))
        val five = StatsFilter.Period(StatsFilter.PeriodKind.CALENDAR, 5)
        val saved = StatsFilter(setOf(RoundType.WOODSMAN, RoundType.BIG_GAME), setOf(BowStyle.AMERICAN_FLATBOW, BowStyle.LONGBOW), five)
        val back = StatsFilter.decode(saved.encode())
        assertEquals(
            StatsFilter(setOf(RoundType.BIG_GAME), setOf(BowStyle.AMERICAN_FLATBOW), five),
            StatsFilter.resolve(back, rounds, BowStyle.COMPOUND_LIMITED),
        )
        assertEquals(setOf(BowStyle.COMPOUND_LIMITED),
            StatsFilter.resolve(StatsFilter.Saved(null, setOf(BowStyle.LONGBOW), null), rounds, BowStyle.COMPOUND_LIMITED).bows)
    }

    @Test fun `matches type, bow and dates`() {
        val f = StatsFilter(setOf(RoundType.BIG_GAME), setOf(BowStyle.COMPOUND_LIMITED, BowStyle.AMERICAN_FLATBOW), rolling1)
        assertTrue(f.matches(r("a", "2026-06-01", BowStyle.COMPOUND_LIMITED), today))
        assertFalse(f.matches(r("b", "2026-06-01", BowStyle.LONGBOW), today))
        assertFalse(f.matches(r("c", "2024-06-01", BowStyle.COMPOUND_LIMITED), today))
        assertFalse(f.matches(r("w", "2026-06-01", BowStyle.COMPOUND_LIMITED, RoundType.WOODSMAN), today))
    }

    @Test fun `toggling never leaves a choice empty`() {
        assertEquals(setOf(RoundType.BIG_GAME), setOf(RoundType.BIG_GAME).toggled(RoundType.BIG_GAME))
        assertEquals(setOf(RoundType.BIG_GAME, RoundType.WOODSMAN), setOf(RoundType.WOODSMAN).toggled(RoundType.BIG_GAME))
    }
}
