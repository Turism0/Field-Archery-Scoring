package uk.co.fnurble.fieldarchery.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The handicap (app 2.2.05; model fitted on the server from the NFAS Nationals and 3D Championships, see
 * docs/HANDICAP.md). Turns a score with any bow style and class into the same performance for another, normally
 * Unlimited Gents (adult), the reference.
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

        /** Ratings and equivalents are shown as a 40-target round. */
        const val RATING_TARGETS = 40

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

/** Your rating: average Unlimited-Gents-equivalent of your latest rounds, as a 40-target score. */
data class HandicapRating(val rating: Int, val rounds: Int)

object HandicapStats {
    /** Unlimited Gents equivalent of a round, out of its own maximum. */
    fun equivalent(model: HandicapModel, r: HandicapRound): Int =
        model.convert(r.points.toDouble(), r.maxPoints.toDouble(), r.archer, r.shootDate.year).roundToInt()

    /** Average of the latest [HandicapModel.RATING_ROUNDS] rounds' equivalent shares × 40 targets × 24; null if too few. */
    fun rating(model: HandicapModel, rounds: List<HandicapRound>): HandicapRating? {
        val latest = rounds.filter { it.maxPoints > 0 }.sortedByDescending { it.shootDate }.take(HandicapModel.RATING_ROUNDS)
        if (latest.size < HandicapModel.RATING_MIN_ROUNDS) return null
        val share = latest.map { model.convert(it.points.toDouble(), it.maxPoints.toDouble(), it.archer, it.shootDate.year) / it.maxPoints }.average()
        return HandicapRating((share * 24 * HandicapModel.RATING_TARGETS).roundToInt(), latest.size)
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
