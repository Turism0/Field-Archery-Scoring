package uk.co.fnurble.nfas.domain

import java.time.LocalDate

enum class RoundStatus { IN_PROGRESS, COMPLETED }

data class Round(
    val id: String,
    val shootDate: LocalDate,
    val targetCount: Int,
    val archerClass: ArcherClass,
    val ageGroup: AgeGroup,
    val bowStyle: BowStyle,
    val status: RoundStatus,
    /** One entry per target, ordered by target number. */
    val targets: List<TargetScore>,
    val location: RoundLocation = RoundLocation(),
    val type: RoundType = RoundType.BIG_GAME,
) {
    val totalPoints: Int get() = targets.sumOf { it.totalPoints }
    val targetsComplete: Int get() = targets.count { it.isComplete }
    val isFullyScored: Boolean get() = targets.size == targetCount && targets.all { it.isComplete }

    fun target(targetNo: Int): TargetScore =
        targets.firstOrNull { it.targetNo == targetNo } ?: newTarget(type, targetNo)

    /** The next target that still needs a score, or null when every target is scored. */
    val firstUnscoredTargetNo: Int? get() = (1..targetCount).firstOrNull { !target(it).isComplete }

    /**
     * No skipping ahead: a target can be opened if it's already scored or it's the next one
     * to score. Going back to correct an earlier target is always allowed.
     */
    fun canGoTo(targetNo: Int): Boolean {
        if (targetNo !in 1..targetCount) return false
        return target(targetNo).isComplete || targetNo == firstUnscoredTargetNo
    }

    /** e.g. "Gents Adult LB". */
    val classLine: String get() = "${archerClass.displayName} ${ageGroup.displayName} ${bowStyle.code}"

    /** "Woodsman · Gents Adult LB"; Big Game rounds just show the class. */
    val typeAndClass: String get() = (if (type == RoundType.WOODSMAN) "${type.displayName} · " else "") + classLine

    val stats: RoundStats get() = RoundStats.of(this)
}

/**
 * Running stats for a round. Hit counts leave out overridden targets (their arrows weren't
 * recorded). The average is per animal, over fully scored targets only: a predator/prey counts
 * as 2 animals and a fan as however many animals it has, so the average is out of 24.
 */
data class RoundStats(
    val totalPoints: Int,
    val targetsScored: Int,
    val targetCount: Int,
    /** Highest score for one animal: 24 Big Game, 30 Woodsman. */
    val maxPerAnimal: Int = BigGameScoring.MAX_PER_ANIMAL,
    /** Average points per animal on fully scored targets (max 24, Woodsman 30), or null before the first is scored. */
    val averagePerAnimal: Double?,
    val innerKills: Int,
    val kills: Int,
    val wounds: Int,
    val misses: Int,
    val byArrow: Map<Int, Int>,
    val overrides: Int,
    /** Animals on fully scored targets (predator/prey = 2, fan = n). */
    val animalsScored: Int,
    /** Points on fully scored targets as a share of the maximum (24 per animal), 0..1. */
    val shareOfMax: Double?,
    /**
     * Rates (0..1) over animals whose arrows were recorded: fully scored, not overridden.
     * Null until there is at least one such animal.
     */
    val firstArrowRate: Double?,
    val killRate: Double?,
    val missRate: Double?,
) {
    companion object {
        fun of(round: Round): RoundStats {
            if (round.type == RoundType.WOODSMAN) return woodsman(round)
            val results = round.targets.filterNot { it.isOverridden }.flatMap { it.animals }
            val scored = results.filterIsInstance<AnimalResult.Scored>()
            val complete = round.targets.filter { it.isComplete }
            val recorded = complete.filterNot { it.isOverridden }.flatMap { it.animals }
            return RoundStats(
                totalPoints = round.totalPoints,
                targetsScored = complete.size,
                targetCount = round.targetCount,
                averagePerAnimal = complete.sumOf { it.animals.size }.let { animals ->
                    if (animals == 0) null else complete.sumOf { it.totalPoints }.toDouble() / animals
                },
                innerKills = scored.count { it.hit == Hit.INNER_KILL },
                kills = scored.count { it.hit == Hit.KILL },
                wounds = scored.count { it.hit == Hit.WOUND },
                misses = results.count { it == AnimalResult.Missed },
                byArrow = (1..BigGameScoring.MAX_ARROWS).associateWith { n -> scored.count { it.arrowNo == n } },
                overrides = round.targets.count { it.isOverridden },
                animalsScored = complete.sumOf { it.animals.size },
                shareOfMax = complete.sumOf { it.maxPoints }.let { max ->
                    if (max == 0) null else complete.sumOf { it.totalPoints }.toDouble() / max
                },
                firstArrowRate = recorded.rate { it is AnimalResult.Scored && it.arrowNo == 1 },
                killRate = recorded.rate { it is AnimalResult.Scored && it.hit != Hit.WOUND },
                missRate = recorded.rate { it == AnimalResult.Missed },
            )
        }
    }
}

/**
 * Woodsman stats: one animal per target. Kills / wounds / misses count arrows; byArrow is which arrow killed;
 * rates are per target over finished, not-overridden targets (first-arrow kill, killed at all, scored nothing).
 */
private fun woodsman(round: Round): RoundStats {
    val recordedTargets = round.targets.filterNot { it.isOverridden }
    val arrows = recordedTargets.flatMap { it.arrows }
    val complete = round.targets.filter { it.isComplete }
    val recorded = complete.filterNot { it.isOverridden }
    fun rate(predicate: (TargetScore) -> Boolean): Double? =
        if (recorded.isEmpty()) null else recorded.count(predicate).toDouble() / recorded.size
    return RoundStats(
        totalPoints = round.totalPoints,
        targetsScored = complete.size,
        targetCount = round.targetCount,
        maxPerAnimal = WoodsmanScoring.MAX_PER_TARGET,
        averagePerAnimal = if (complete.isEmpty()) null else complete.sumOf { it.totalPoints }.toDouble() / complete.size,
        innerKills = 0,
        kills = arrows.count { it == WoodsmanArrow.KILL },
        wounds = arrows.count { it == WoodsmanArrow.WOUND },
        misses = arrows.count { it == WoodsmanArrow.MISS },
        byArrow = (1..WoodsmanScoring.MAX_ARROWS).associateWith { n ->
            recordedTargets.count { it.arrows.getOrNull(n - 1) == WoodsmanArrow.KILL }
        },
        overrides = round.targets.count { it.isOverridden },
        animalsScored = complete.size,
        shareOfMax = if (complete.isEmpty()) null
        else complete.sumOf { it.totalPoints }.toDouble() / (complete.size * WoodsmanScoring.MAX_PER_TARGET),
        firstArrowRate = rate { it.arrows.firstOrNull() == WoodsmanArrow.KILL },
        killRate = rate { it.arrows.lastOrNull() == WoodsmanArrow.KILL },
        missRate = rate { it.calculatedPoints == 0 },
    )
}

/** A new, unscored target for a round of this type. */
fun newTarget(type: RoundType, targetNo: Int): TargetScore = when (type) {
    RoundType.BIG_GAME -> TargetScore(targetNo)
    RoundType.WOODSMAN -> TargetScore(targetNo, TargetType.WOODSMAN, animals = emptyList())
}

private fun List<AnimalResult>.rate(predicate: (AnimalResult) -> Boolean): Double? =
    if (isEmpty()) null else count(predicate).toDouble() / size
