package uk.co.fieldarcheryscoring.domain

import java.time.LocalDate

/*
 * History and stats (app 2.2): personal bests, trends and season summaries, worked out on the phone from your own
 * finished rounds (not ones scored for friends or guests). Averages are per animal (Big Game: predator/prey = 2 animals,
 * a fan = however many animals it has; out of 24) or per target (Woodsman, out of 30), as on the scorecard.
 */

/** Rounds are only compared with the same round type, bow style and number of targets. */
data class BestKey(val type: RoundType, val bowStyle: BowStyle, val targetCount: Int)

// Fun shots (3.1) are left out: bests compare the total without them, on the targets without them.
val Round.bestKey: BestKey get() = BestKey(type, bowStyle, statsTargetCount)

/** Oldest first; rounds shot on the same day in the order they were started. */
private val shotOrder = compareBy<Round>({ it.shootDate }, { it.createdAtMillis }, { it.id })

/** Personal bests for one round type, bow style and number of targets. */
data class PersonalBest(
    val key: BestKey,
    /** Highest total (fully scored rounds only). */
    val best: Round,
    /** Fully scored rounds of this kind. */
    val roundsShot: Int,
    val averageTotal: Double,
)

/** Best average for a round type and bow style, over full-length rounds of any size. */
data class BestAverage(val type: RoundType, val bowStyle: BowStyle, val round: Round, val average: Double)

object PersonalStats {
    /** Rounds shorter than this (practice ends) don't count for the best average. Half a course is 18–20. */
    const val MIN_TARGETS_FOR_BEST_AVERAGE = 18

    /** Bests by round type, bow style and number of targets; most-shot first within a type and bow. */
    fun bests(rounds: List<Round>): List<PersonalBest> =
        rounds.filter { it.status == RoundStatus.COMPLETED && it.isFullyScored }
            .groupBy { it.bestKey }
            .map { (key, list) ->
                PersonalBest(
                    key = key,
                    // Highest total; the first time it was shot wins a tie.
                    best = list.sortedWith(shotOrder).maxBy { it.statsPoints },
                    roundsShot = list.size,
                    averageTotal = list.map { it.statsPoints }.average(),
                )
            }
            .sortedWith(compareBy<PersonalBest>({ it.key.type.ordinal }, { it.key.bowStyle.ordinal }, { -it.roundsShot }, { it.key.targetCount }))

    /** Best average per animal (per target for Woodsman) for each round type and bow style. */
    fun bestAverages(rounds: List<Round>): List<BestAverage> =
        rounds.filter { it.status == RoundStatus.COMPLETED && it.isFullyScored && it.statsTargetCount >= MIN_TARGETS_FOR_BEST_AVERAGE }
            .groupBy { it.type to it.bowStyle }
            .mapNotNull { (k, list) ->
                val best = list.sortedWith(shotOrder).maxByOrNull { it.stats.averagePerAnimal ?: -1.0 } ?: return@mapNotNull null
                best.stats.averagePerAnimal?.let { BestAverage(k.first, k.second, best, it) }
            }
            .sortedWith(compareBy({ it.type.ordinal }, { it.bowStyle.ordinal }))

    // ---- The PB badge (3.1.08, Pete): only your current best, by average per animal, per round type and bow style ----

    /** Average per animal (Woodsman: per target), fun shots left out; null when nothing's scored. */
    fun pbAverage(r: Round): Double? = r.stats.averagePerAnimal

    /**
     * The rounds that are your current personal best: the highest average per animal among finished, fully scored
     * rounds of each round type and bow style, whatever their length (the first shot wins a tie).
     */
    fun personalBestIds(rounds: List<Round>): Set<String> =
        rounds.filter { it.status == RoundStatus.COMPLETED && it.isFullyScored && pbAverage(it) != null }
            .groupBy { it.type to it.bowStyle }
            .values.mapNotNull { list -> list.sortedWith(shotOrder).maxByOrNull { pbAverage(it)!! }?.id }
            .toSet()

    /** The best earlier round of the same type and bow style by average (what [round] had to beat), or null. */
    fun previousBest(round: Round, rounds: List<Round>): Round? =
        rounds.filter {
            it.id != round.id && it.status == RoundStatus.COMPLETED && it.isFullyScored && pbAverage(it) != null &&
                it.type == round.type && it.bowStyle == round.bowStyle && shotOrder.compare(it, round) < 0
        }.sortedWith(shotOrder).maxByOrNull { pbAverage(it)!! }

    // ---- Trends ----

    /** One finished round on the trend chart. [rolling] is the average of this and up to 4 rounds before it. */
    data class TrendPoint(val round: Round, val date: LocalDate, val average: Double, val rolling: Double)

    /** Average per animal (per target for Woodsman) for each finished round of a type and bow style, oldest first. */
    fun trend(rounds: List<Round>, type: RoundType, bowStyle: BowStyle?, window: Int = 5): List<TrendPoint> {
        val list = rounds.filter { it.status == RoundStatus.COMPLETED && it.type == type && (bowStyle == null || it.bowStyle == bowStyle) }
            .sortedWith(shotOrder)
            .mapNotNull { r -> r.stats.averagePerAnimal?.let { r to it } }
        return list.mapIndexed { i, (r, avg) ->
            val recent = list.subList(maxOf(0, i - window + 1), i + 1).map { it.second }
            TrendPoint(r, r.shootDate, avg, recent.average())
        }
    }

    // ---- Seasons (calendar years) ----

    data class Season(
        val year: Int,
        val type: RoundType,
        val rounds: Int,
        val targets: Int,
        /** Distinct shoot locations (named ones). */
        val locations: Int,
        /** All the year's scored animals (or Woodsman targets) together. */
        val stats: RoundStats,
        /** Highest total of the year, any size of round. */
        val bestRound: Round?,
        /** Per bow style: rounds and average. */
        val byBow: List<Triple<BowStyle, Int, Double?>>,
        /** New personal bests set that year. */
        val personalBests: Int,
    )

    /** One summary per year and round type, newest year first. */
    fun seasons(rounds: List<Round>): List<Season> {
        val finished = rounds.filter { it.status == RoundStatus.COMPLETED }
        val pbs = personalBestIds(finished)
        return finished.groupBy { it.shootDate.year to it.type }
            .map { (k, list) ->
                Season(
                    year = k.first,
                    type = k.second,
                    rounds = list.size,
                    targets = list.sumOf { r -> r.targets.count { it.isComplete } },
                    locations = list.mapNotNull { it.location.name?.trim()?.lowercase() }.distinct().size,
                    stats = combined(list).stats,
                    bestRound = list.sortedWith(shotOrder).maxByOrNull { it.statsPoints },
                    byBow = list.groupBy { it.bowStyle }.toSortedMap(compareBy<BowStyle> { it.ordinal })
                        .map { (bow, l) -> Triple(bow, l.size, combined(l).stats.averagePerAnimal) },
                    personalBests = list.count { it.id in pbs },
                )
            }
            .sortedWith(compareBy<Season>({ -it.year }, { it.type.ordinal }))
    }

    /** All these rounds' targets as one long round, so the usual round stats give the combined figures. */
    fun combined(rounds: List<Round>): Round {
        val first = rounds.first()
        val targets = rounds.flatMap { it.targets }.mapIndexed { i, t -> t.copy(targetNo = i + 1) }
        return first.copy(id = "combined", targetCount = targets.size, targets = targets)
    }

    // ---- Filtering the history list ----

    /** History period: everything, the last 12 months, a calendar year or chosen dates. */
    sealed interface Period {
        fun contains(date: LocalDate, today: LocalDate): Boolean
        val label: String

        data object All : Period {
            override fun contains(date: LocalDate, today: LocalDate) = true
            override val label = "All time"
        }

        data object Last12Months : Period {
            override fun contains(date: LocalDate, today: LocalDate) = date > today.minusYears(1) && date <= today
            override val label = "Last 12 months"
        }

        data class InYear(val year: Int) : Period {
            override fun contains(date: LocalDate, today: LocalDate) = date.year == year
            override val label get() = "$year"
        }

        /** Either end may be open. */
        data class Between(val from: LocalDate?, val to: LocalDate?) : Period {
            override fun contains(date: LocalDate, today: LocalDate) =
                (from == null || date >= from) && (to == null || date <= to)
            override val label get() = "Dates…"
        }
    }

    /** Years that have rounds, newest first (for the period choice). */
    fun years(dates: List<LocalDate>): List<Int> = dates.map { it.year }.distinct().sortedDescending()
}

/** Score history order (3.2.01, Pete): by date or by average per animal, either way. */
enum class HistorySort(val label: String) {
    DATE_DESC("Newest first"), DATE_ASC("Oldest first"), AVG_DESC("Highest average"), AVG_ASC("Lowest average");

    /**
     * [newestFirst] (as the database gives them) in this order. Average: rounds with none (nothing scored) go last;
     * a tie goes newest first.
     */
    fun <T> sort(newestFirst: List<T>, average: (T) -> Double?): List<T> = when (this) {
        DATE_DESC -> newestFirst
        DATE_ASC -> newestFirst.reversed()
        AVG_DESC, AVG_ASC -> {
            val (scored, none) = newestFirst.partition { average(it) != null }
            val byAvg = if (this == AVG_DESC) scored.sortedByDescending { average(it)!! } else scored.sortedBy { average(it)!! }
            byAvg + none   // sortedBy is stable: a tie keeps newest first
        }
    }
}
