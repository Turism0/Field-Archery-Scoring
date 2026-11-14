package uk.co.fieldarcheryscoring.domain

import java.time.LocalDate

/**
 * The Stats screen's filters (3.2, Pete): round types and bow styles (any number of each, never none) and a date range.
 * The same rules as the web app's domain/statsFilter.ts.
 */
data class StatsFilter(val types: Set<RoundType>, val bows: Set<BowStyle>, val period: Period) {

    /** ALL time; the last [years] CALENDAR years including this one (1 = this year so far); or the last [years] ROLLING to today. */
    enum class PeriodKind { ALL, CALENDAR, ROLLING }

    data class Period(val kind: PeriodKind, val years: Int = 0) {
        fun contains(date: LocalDate, today: LocalDate): Boolean = when (kind) {
            PeriodKind.ALL -> true
            PeriodKind.CALENDAR -> date.year >= today.year - years + 1
            PeriodKind.ROLLING -> date.isAfter(today.minusYears(years.toLong()))   // after the same date [years] years ago
        }

        /** e.g. "Last 1 year (rolling)", "This year (2026)", "Last 3 years (2024–2026)", "All time". */
        fun label(today: LocalDate): String = when (kind) {
            PeriodKind.ALL -> "All time"
            PeriodKind.ROLLING -> "Last $years year${if (years == 1) "" else "s"} (rolling)"
            PeriodKind.CALENDAR -> if (years == 1) "This year (${today.year})" else "Last $years years (${today.year - years + 1}–${today.year})"
        }
    }

    fun matches(r: Round, today: LocalDate): Boolean = r.type in types && r.bowStyle in bows && period.contains(r.shootDate, today)

    /** For saving on the phone: "types=BIG_GAME,WOODSMAN;bows=CL,AFB;period=ROLLING:1". */
    fun encode(): String =
        "types=${types.joinToString(",") { it.name }};bows=${bows.joinToString(",") { it.code }};period=${period.kind.name}:${period.years}"

    companion object {
        val YEARS = listOf(1, 2, 3, 5, 10)
        val DEFAULT_PERIOD = Period(PeriodKind.ROLLING, 1)

        /** Every period offered, in menu order. */
        val PERIODS: List<Period> =
            YEARS.map { Period(PeriodKind.ROLLING, it) } + YEARS.map { Period(PeriodKind.CALENDAR, it) } + Period(PeriodKind.ALL, 0)

        /** What [encode] wrote, as far as it still makes sense (null parts are filled in by [resolve]). */
        fun decode(text: String?): Saved? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(";").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val types = parts["types"]?.split(",")?.mapNotNull { n -> RoundType.entries.firstOrNull { it.name == n } }?.toSet()
            val bows = parts["bows"]?.split(",")?.mapNotNull { BowStyle.fromCode(it) }?.toSet()
            val period = parts["period"]?.split(":")?.let { p ->
                val kind = PeriodKind.entries.firstOrNull { it.name == p.getOrNull(0) }
                val years = p.getOrNull(1)?.toIntOrNull() ?: 0
                kind?.let { Period(it, if (it == PeriodKind.ALL) 0 else years) }
            }?.takeIf { it in PERIODS }
            return Saved(types, bows, period)
        }

        /** Round types you have finished rounds of, in order. */
        fun availableTypes(rounds: List<Round>): List<RoundType> = rounds.map { it.type }.distinct().sortedBy { it.ordinal }

        /** Bow styles you have finished rounds with (the only ones offered), in order. */
        fun availableBows(rounds: List<Round>): List<BowStyle> = rounds.map { it.bowStyle }.distinct().sortedBy { it.ordinal }

        /**
         * The filter to use: the last choice ([saved]) less anything you no longer have rounds for. The first time (or when
         * nothing saved is left): every round type, your profile's bow style if you've shot with it (else the one you've
         * shot most), and the last year rolling.
         */
        fun resolve(saved: Saved?, rounds: List<Round>, profileBow: BowStyle?): StatsFilter {
            val types = availableTypes(rounds)
            val bows = availableBows(rounds)
            val keptTypes = saved?.types.orEmpty().filter { it in types }.toSet()
            val keptBows = saved?.bows.orEmpty().filter { it in bows }.toSet()
            val defaultBow = profileBow?.takeIf { it in bows }
                ?: rounds.groupingBy { it.bowStyle }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<BowStyle, Int>> { it.value }.thenBy { it.key.ordinal }).firstOrNull()?.key
            return StatsFilter(
                types = keptTypes.ifEmpty { types.toSet() },
                bows = keptBows.ifEmpty { setOfNotNull(defaultBow) },
                period = saved?.period ?: DEFAULT_PERIOD,
            )
        }
    }

    /** A saved choice, each part null when it wasn't saved. */
    data class Saved(val types: Set<RoundType>?, val bows: Set<BowStyle>?, val period: Period?)
}

/** Adds or removes [value], never leaving the set empty. */
fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) (if (size > 1) this - value else this) else this + value
