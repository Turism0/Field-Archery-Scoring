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
) {
    val totalPoints: Int get() = targets.sumOf { it.totalPoints }
    val targetsComplete: Int get() = targets.count { it.isComplete }
    val isFullyScored: Boolean get() = targets.size == targetCount && targets.all { it.isComplete }

    fun target(targetNo: Int): TargetScore =
        targets.firstOrNull { it.targetNo == targetNo } ?: TargetScore(targetNo)

    /** e.g. "Gents Adult LB". */
    val classLine: String get() = "${archerClass.displayName} ${ageGroup.displayName} ${bowStyle.code}"

    val stats: RoundStats get() = RoundStats.of(this)
}

/** Simple counts for the round summary (overridden targets are excluded from hit counts). */
data class RoundStats(
    val innerKills: Int,
    val kills: Int,
    val wounds: Int,
    val misses: Int,
    val byArrow: Map<Int, Int>,
    val overrides: Int,
) {
    companion object {
        fun of(round: Round): RoundStats {
            val results = round.targets.filterNot { it.isOverridden }.flatMap { it.animals }
            val scored = results.filterIsInstance<AnimalResult.Scored>()
            return RoundStats(
                innerKills = scored.count { it.hit == Hit.INNER_KILL },
                kills = scored.count { it.hit == Hit.KILL },
                wounds = scored.count { it.hit == Hit.WOUND },
                misses = results.count { it == AnimalResult.Missed },
                byArrow = (1..BigGameScoring.MAX_ARROWS).associateWith { n -> scored.count { it.arrowNo == n } },
                overrides = round.targets.count { it.isOverridden },
            )
        }
    }
}
