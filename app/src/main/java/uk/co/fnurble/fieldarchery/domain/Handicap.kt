package uk.co.fnurble.fieldarchery.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import java.time.LocalDate

/**
 * Handicaps (app 2.9, Pete). Two kinds, both for Big Game:
 *
 * - **Multipliers** for a class / bow style handicap competition: score × multiplier. Each bow style and class is
 *   measured against the maximum for the round (Pete): multiplier = maximum ÷ that bow and class's typical score, so a
 *   typical archer of any class comes out at the maximum. "Typical" is an average archer of that bow and class at a
 *   recent championship, from the model fitted on the server from the NFAS Nationals and 3D Championships (see
 *   docs/HANDICAP.md): typical share = 1 / (1 + e^−(BASE + offset)). Shown to 2 decimals, and that's the number used.
 * - **Personal handicap** ([PersonalHandicap]), like golf: par is 960, the maximum on a 40-target course.
 *
 * The model also turns a score with one bow style and class into the same performance for another ([convert], the
 * calculator). The out-of-1000 "handicap points" and rating were removed in 2.9 (Pete).
 *
 * A score is used as its share of the maximum (24 an animal), on a logit scale; each bow style and class has an
 * offset from Unlimited Gents (the fit's arithmetic, never shown), which changes over the years. Paper faces and 3D
 * targets are averaged: half of each bow's 3D term is always included. Missing factors are 0.
 */
data class HandicapModel(
    val modelId: Int,
    val dataThroughYear: Int,
    val yearZero: Int = 2014,
    /** "BOW:LB", "SLOPE:LB", "BOW3D:LB", "CLASS:F-ADULT", "BOWSEX:LB", "BASE:ALL" (API 2.9) → value. */
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

    /** The level of a typical championship (server 2.9); null from older servers. */
    val base: Double? get() = factors["BASE:ALL"]

    /** A typical score for this bow style and class as a share of the maximum, or null without [base]. */
    fun typicalShare(a: Archer, year: Int = dataThroughYear): Double? =
        base?.let { 1 / (1 + exp(-(it + offset(a.bowStyle, a.archerClass, a.ageGroup, year)))) }

    /** Competition multiplier: the maximum ÷ a typical score, to 2 decimals (score × this). Null without [base]. */
    fun multiplier(a: Archer, year: Int = dataThroughYear): Double? = typicalShare(a, year)?.let { Math.round(100 / it) / 100.0 }

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

        /** Score × multiplier, rounded. */
        fun applyMultiplier(points: Int, multiplier: Double): Int = (points * multiplier).roundToInt()
    }
}

/**
 * Personal handicap (2.9, Pete: "like golf, but par is the maximum possible score off a 40 course"). Big Game, one per
 * bow style. Par is [PAR] (24 × 40). Each fully scored round is scaled to 40 targets (score × 40 ÷ targets); your usual
 * score is the average of the best 40% (at least 3) of your last [MAX_ROUNDS] with that bow, as golf's best 8 of 20;
 * fewer than [MIN_ROUNDS] and there's no handicap yet. Handicap = par − usual score. Net score = score + handicap,
 * scaled to the targets shot (a 36-target round adds 36/40 of it), so a usual round nets about par.
 */
object PersonalHandicap {
    const val PAR = 960
    const val PAR_TARGETS = 40
    const val MAX_ROUNDS = 20
    const val MIN_ROUNDS = 3

    /** One finished, fully scored Big Game round. [order]: tells apart rounds on the same day (when it was started). */
    data class Entry(val shootDate: LocalDate, val order: Long, val bowStyle: BowStyle, val points: Int, val targetCount: Int) {
        /** The score scaled to 40 targets. */
        val scaled: Double get() = points * PAR_TARGETS.toDouble() / targetCount
    }

    data class Handicap(
        val bowStyle: BowStyle,
        val handicap: Int,
        /** Average of the counted rounds, scaled to 40 targets. */
        val usual: Double,
        /** The rounds that count (best first). */
        val counted: List<Entry>,
        /** How many recent rounds were looked at (up to [MAX_ROUNDS]). */
        val considered: Int,
    )

    /** How many of [n] recent rounds count: 40% (8 of 20), at least [MIN_ROUNDS]; 0 when there are too few. */
    fun counting(n: Int): Int = if (n < MIN_ROUNDS) 0 else maxOf(MIN_ROUNDS, Math.round(n * 0.4).toInt()).coerceAtMost(n)

    private fun before(a: Entry, b: Entry) = a.shootDate < b.shootDate || (a.shootDate == b.shootDate && a.order < b.order)

    /** The handicap with [bow] from [entries]; with [before], only rounds shot before that one (the handicap you had then). */
    fun of(entries: List<Entry>, bow: BowStyle, before: Entry? = null): Handicap? {
        val recent = entries.filter { it.bowStyle == bow && it.targetCount > 0 && (before == null || before(it, before)) }
            .sortedWith(compareByDescending<Entry> { it.shootDate }.thenByDescending { it.order })
            .take(MAX_ROUNDS)
        val k = counting(recent.size)
        if (k == 0) return null
        val best = recent.sortedByDescending { it.scaled }.take(k)
        val usual = best.map { it.scaled }.average()
        return Handicap(bow, (PAR - usual).roundToInt(), usual, best, recent.size)
    }

    /** A handicap for every bow style with enough rounds, most rounds first. */
    fun all(entries: List<Entry>): List<Handicap> =
        entries.groupBy { it.bowStyle }.entries.sortedByDescending { it.value.size }.mapNotNull { of(entries, it.key) }

    /** The handicap allowance for [targetCount] targets (scaled from 40). */
    fun allowance(handicap: Int, targetCount: Int): Int = (handicap * targetCount / PAR_TARGETS.toDouble()).roundToInt()

    /** Net score: the score plus the handicap scaled to the targets shot. */
    fun net(points: Int, targetCount: Int, handicap: Int): Int = points + allowance(handicap, targetCount)

    /** Par for [targetCount] targets (24 a target). */
    fun parFor(targetCount: Int): Int = 24 * targetCount
}

/** Your own finished, fully scored Big Game round for the personal handicap; null otherwise. */
fun Round.handicapEntry(): PersonalHandicap.Entry? =
    // Fun shots (3.1) are left out: the points and targets without them.
    if (type == RoundType.BIG_GAME && status == RoundStatus.COMPLETED && isFullyScored && statsTargetCount > 0)
        PersonalHandicap.Entry(shootDate, createdAtMillis, bowStyle, statsPoints, statsTargetCount)
    else null
