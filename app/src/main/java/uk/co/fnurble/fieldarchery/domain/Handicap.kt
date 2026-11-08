package uk.co.fnurble.fieldarchery.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The handicap (app 2.2.05; model fitted on the server from the NFAS Nationals and 3D Championships, see
 * docs/HANDICAP.md). Turns a score with any bow style and class into the same performance for another, and into
 * **handicap points** (2.2.06): out of 1000, the same for the same performance whatever the bow or class. No bow
 * style is the yardstick: the scale is set at the middle (median) of all bow styles and classes, so traditional and
 * compound archers are measured on the same neutral scale (Pete: not "compared with Unlimited").
 *
 * (Inside the model Unlimited Gents has zero offsets, but that's only the fit's arithmetic and is never shown.)
 *
 * A score is used as its share of the maximum (24 an animal), on a logit scale; each bow style and class has an
 * offset from Unlimited Gents, which changes over the years. Paper faces and 3D targets are averaged (Pete, Nov 2026):
 * half of each bow's 3D term is always included. Missing factors are 0.
 */
data class HandicapModel(
    val modelId: Int,
    val dataThroughYear: Int,
    val yearZero: Int = 2014,
    /** "BOW:LB", "SLOPE:LB", "BOW3D:LB", "CLASS:F-ADULT", "BOWSEX:LB" → value. */
    val factors: Map<String, Double>,
) {
    private fun f(kind: String, code: String) = factors["$kind:$code"] ?: 0.0

    /** Logit-scale offset of a bow style and class from Unlimited Gents in [year] (clamped to the data). */
    fun offset(bow: BowStyle, archerClass: ArcherClass, ageGroup: AgeGroup, year: Int): Double {
        val y = year.coerceIn(2002, dataThroughYear)
        val code = bow.code
        val ladies = archerClass == ArcherClass.LADIES
        return f("BOW", code) + f("CLASS", classCode(archerClass, ageGroup)) + (if (ladies) f("BOWSEX", code) else 0.0) +
            f("SLOPE", code) * (y - yearZero) / 10.0 + 0.5 * f("BOW3D", code)
    }

    /**
     * The same performance as [points] out of [maxPoints] with one bow / class, for another (by default Unlimited
     * Gents), out of the same maximum.
     */
    fun convert(
        points: Double,
        maxPoints: Double,
        from: Archer,
        year: Int,
        to: Archer = Archer.REFERENCE,
    ): Double {
        if (maxPoints <= 0) return 0.0
        val p = (points / maxPoints).coerceIn(0.01, 0.99)
        val z = ln(p / (1 - p)) - offset(from.bowStyle, from.archerClass, from.ageGroup, year) +
            offset(to.bowStyle, to.archerClass, to.ageGroup, year)
        return maxPoints / (1 + exp(-z))
    }

    /** The middle of all bow styles and classes in [year]: what handicap points are measured against. */
    fun neutralOffset(year: Int): Double {
        val all = BowStyle.entries.flatMap { b ->
            ArcherClass.entries.flatMap { c -> AgeGroup.entries.map { a -> offset(b, c, a, year) } }
        }.sorted()
        return (all[(all.size - 1) / 2] + all[all.size / 2]) / 2
    }

    /** Handicap points (0–1000) for [points] out of [maxPoints] with this bow and class in [year]. */
    fun handicapPoints(points: Double, maxPoints: Double, from: Archer, year: Int): Double {
        if (maxPoints <= 0) return 0.0
        val p = (points / maxPoints).coerceIn(0.01, 0.99)
        val z = ln(p / (1 - p)) - offset(from.bowStyle, from.archerClass, from.ageGroup, year) + neutralOffset(year)
        return 1000 / (1 + exp(-z))
    }

    /** A bow style and class. */
    data class Archer(val bowStyle: BowStyle, val archerClass: ArcherClass, val ageGroup: AgeGroup) {
        companion object {
            val REFERENCE = Archer(BowStyle.UNLIMITED, ArcherClass.GENTS, AgeGroup.ADULT)
        }
    }

    companion object {
        /** The server's class codes: M-ADULT, F-JUNIOR, M-CUB … */
        fun classCode(archerClass: ArcherClass, ageGroup: AgeGroup) =
            (if (archerClass == ArcherClass.LADIES) "F" else "M") + "-" + ageGroup.name

        /** Rating: the average of this many of your latest finished Big Game rounds. */
        const val RATING_ROUNDS = 10

        /** Fewer rounds than this and there's no rating yet. */
        const val RATING_MIN_ROUNDS = 3
    }
}

/** One finished Big Game round as the handicap sees it. */
data class HandicapRound(
    val shootDate: java.time.LocalDate,
    val points: Int,
    /** 24 × animals scored (predator/prey = 2, fan = n). */
    val maxPoints: Int,
    val archer: HandicapModel.Archer,
)

/** Your rating: average handicap points (0–1000) of your latest rounds. */
data class HandicapRating(val rating: Int, val rounds: Int)

object HandicapStats {
    /** Handicap points (0–1000) for a round. */
    fun points(model: HandicapModel, r: HandicapRound): Int =
        model.handicapPoints(r.points.toDouble(), r.maxPoints.toDouble(), r.archer, r.shootDate.year).roundToInt()

    /** Average handicap points of the latest [HandicapModel.RATING_ROUNDS] rounds; null if fewer than the minimum. */
    fun rating(model: HandicapModel, rounds: List<HandicapRound>): HandicapRating? {
        val latest = rounds.filter { it.maxPoints > 0 }.sortedByDescending { it.shootDate }.take(HandicapModel.RATING_ROUNDS)
        if (latest.size < HandicapModel.RATING_MIN_ROUNDS) return null
        val avg = latest.map { model.handicapPoints(it.points.toDouble(), it.maxPoints.toDouble(), it.archer, it.shootDate.year) }.average()
        return HandicapRating(avg.roundToInt(), latest.size)
    }
}

/** Your own finished Big Game round for the handicap (fully scored targets only); null for Woodsman. */
fun Round.handicapRound(): HandicapRound? {
    if (type != RoundType.BIG_GAME) return null
    val done = targets.filter { it.isComplete }
    val max = done.sumOf { it.maxPoints }
    if (max == 0) return null
    return HandicapRound(shootDate, done.sumOf { it.totalPoints }, max, HandicapModel.Archer(bowStyle, archerClass, ageGroup))
}
