package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PersonalStatsTest {

    /** A finished Big Game round where every target scored [perTarget] (set as overrides, so any total works). */
    private fun round(
        id: String,
        date: String,
        perTarget: Int,
        targets: Int = 36,
        bow: BowStyle = BowStyle.LONGBOW,
        created: Long = 0,
        status: RoundStatus = RoundStatus.COMPLETED,
        scored: Int = targets,
        type: RoundType = RoundType.BIG_GAME,
        location: String? = null,
    ) = Round(
        id = id,
        shootDate = LocalDate.parse(date),
        targetCount = targets,
        archerClass = ArcherClass.GENTS,
        ageGroup = AgeGroup.ADULT,
        bowStyle = bow,
        status = status,
        targets = (1..scored).map { newTarget(type, it).withOverride(perTarget) },
        type = type,
        createdAtMillis = created,
        location = RoundLocation(LocationStatus.SET, location),
    )

    @Test
    fun bestTotalPerTypeBowAndTargets() {
        val rounds = listOf(
            round("a", "2026-03-01", 15),            // 540
            round("b", "2026-04-01", 18),            // 648, best 36 LB
            round("c", "2026-05-01", 20, targets = 40), // 800, its own 40-target best
            round("d", "2026-06-01", 22, bow = BowStyle.BAREBOW), // other bow
            round("e", "2026-07-01", 23, scored = 30), // not fully scored: left out
            round("f", "2026-08-01", 24, status = RoundStatus.IN_PROGRESS), // not finished
        )
        val bests = PersonalStats.bests(rounds)
        val lb36 = bests.single { it.key == BestKey(RoundType.BIG_GAME, BowStyle.LONGBOW, 36) }
        assertEquals("b", lb36.best.id)
        assertEquals(2, lb36.roundsShot)
        assertEquals(594.0, lb36.averageTotal, 0.001)
        assertEquals("c", bests.single { it.key.targetCount == 40 }.best.id)
        assertEquals("d", bests.single { it.key.bowStyle == BowStyle.BAREBOW }.best.id)
        assertEquals(3, bests.size)
    }

    @Test
    fun tieGoesToTheFirstTimeShot() {
        val rounds = listOf(round("later", "2026-05-01", 18), round("first", "2026-04-01", 18))
        assertEquals("first", PersonalStats.bests(rounds).single().best.id)
        // 3.1.08: the current PB; a tie goes to the first shot.
        assertEquals(setOf("first"), PersonalStats.personalBestIds(rounds))
    }

    @Test
    fun pbIsTheCurrentBestAveragePerTypeAndBow() {
        // 3.1.08 (Pete): only the current best, by average per animal, per round type and bow style, any length.
        val rounds = listOf(
            round("1", "2026-01-01", 15),
            round("2", "2026-02-01", 17),
            round("3", "2026-03-01", 16),
            round("4", "2026-04-01", 18),
            round("5", "2026-04-01", 19, created = 10),
            round("6", "2026-05-01", 20, targets = 40), // 36s and 40s compared on average: this is the LB PB
        )
        assertEquals(setOf("6"), PersonalStats.personalBestIds(rounds))
        val more = rounds + round("bb", "2026-06-01", 14, bow = BowStyle.BAREBOW) + round("short", "2026-07-01", 21, targets = 3)
        assertEquals(setOf("bb", "short"), PersonalStats.personalBestIds(more))
        assertEquals("4", PersonalStats.previousBest(rounds.first { it.id == "5" }, rounds)?.id)
        assertNull(PersonalStats.previousBest(rounds.first { it.id == "1" }, rounds))
    }

    @Test
    fun bestAverageIgnoresShortRounds() {
        val rounds = listOf(
            round("short", "2026-01-01", 24, targets = 10),
            round("half", "2026-02-01", 19, targets = 18),
            round("full", "2026-03-01", 18),
        )
        val best = PersonalStats.bestAverages(rounds).single()
        assertEquals("half", best.round.id)
        assertEquals(19.0, best.average, 0.001)
    }

    @Test
    fun trendIsOldestFirstWithRollingAverage() {
        val rounds = listOf(
            round("c", "2026-03-01", 12), round("a", "2026-01-01", 10), round("b", "2026-02-01", 11),
            round("w", "2026-02-15", 20, type = RoundType.WOODSMAN), // other type
        )
        val t = PersonalStats.trend(rounds, RoundType.BIG_GAME, BowStyle.LONGBOW, window = 2)
        assertEquals(listOf("a", "b", "c"), t.map { it.round.id })
        assertEquals(listOf(10.0, 11.0, 12.0), t.map { it.average })
        assertEquals(listOf(10.0, 10.5, 11.5), t.map { it.rolling })
        assertEquals(1, PersonalStats.trend(rounds, RoundType.WOODSMAN, null).size)
    }

    @Test
    fun seasonsAreCalendarYearsPerType() {
        val rounds = listOf(
            round("a", "2025-12-31", 10, location = "Club A"),
            round("b", "2026-01-01", 12, location = "Club A"),
            round("c", "2026-06-01", 16, targets = 40, location = "club a "), // same place, different case
            round("d", "2026-07-01", 14, bow = BowStyle.BAREBOW, location = "Club B"),
            round("w", "2026-08-01", 25, targets = 20, type = RoundType.WOODSMAN),
        )
        val seasons = PersonalStats.seasons(rounds)
        assertEquals(listOf(2026 to RoundType.BIG_GAME, 2026 to RoundType.WOODSMAN, 2025 to RoundType.BIG_GAME),
            seasons.map { it.year to it.type })
        val bg = seasons.first()
        assertEquals(3, bg.rounds)
        assertEquals(36 + 40 + 36, bg.targets)
        assertEquals(2, bg.locations)
        assertEquals("c", bg.bestRound?.id)
        // (36×12 + 40×16 + 36×14) / 112 animals
        assertEquals((432 + 640 + 504) / 112.0, bg.stats.averagePerAnimal!!, 0.001)
        assertEquals(listOf(BowStyle.BAREBOW, BowStyle.LONGBOW), bg.byBow.map { it.first })
        assertEquals(2, bg.personalBests) // 3.1.08: current PBs shot that season, LB c and BB d
        assertEquals(25.0, seasons[1].stats.averagePerAnimal!!, 0.001)
    }

    @Test
    fun periods() {
        val today = LocalDate.of(2026, 11, 8)
        assertTrue(PersonalStats.Period.Last12Months.contains(LocalDate.of(2025, 11, 9), today))
        assertTrue(!PersonalStats.Period.Last12Months.contains(LocalDate.of(2025, 11, 8), today))
        assertTrue(PersonalStats.Period.InYear(2025).contains(LocalDate.of(2025, 1, 1), today))
        val between = PersonalStats.Period.Between(LocalDate.of(2026, 3, 1), null)
        assertTrue(between.contains(LocalDate.of(2026, 3, 1), today))
        assertTrue(!between.contains(LocalDate.of(2026, 2, 28), today))
        assertEquals(listOf(2026, 2025), PersonalStats.years(listOf(LocalDate.of(2025, 5, 1), LocalDate.of(2026, 1, 1))))
    }
}
