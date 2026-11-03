package uk.co.fnurble.fieldarchery.domain

/** Where an arrow landed. Inner kill only counts on the 1st arrow at an animal. */
enum class Hit(val displayName: String) {
    INNER_KILL("Inner kill"),
    KILL("Kill"),
    WOUND("Wound"),
}

/**
 * Big Game scoring values.
 *
 *  Arrow 1: inner kill 24, kill 20, wound 16
 *  Arrow 2: kill 14, wound 10
 *  Arrow 3: kill 8, wound 4
 *  No scoring arrow: 0
 *
 * The arrow number is counted per animal (the 1st arrow shot at the prey is scored as a 1st arrow).
 */
object BigGameScoring {
    const val MAX_ARROWS = 3
    const val MAX_PER_ANIMAL = 24

    fun allowedHits(arrowNo: Int): List<Hit> = when (arrowNo) {
        1 -> listOf(Hit.INNER_KILL, Hit.KILL, Hit.WOUND)
        2, 3 -> listOf(Hit.KILL, Hit.WOUND)
        else -> emptyList()
    }

    fun isValid(arrowNo: Int, hit: Hit): Boolean = hit in allowedHits(arrowNo)

    fun points(arrowNo: Int, hit: Hit): Int {
        require(isValid(arrowNo, hit)) { "$hit is not valid on arrow $arrowNo" }
        return when (arrowNo) {
            1 -> when (hit) {
                Hit.INNER_KILL -> 24
                Hit.KILL -> 20
                Hit.WOUND -> 16
            }
            2 -> if (hit == Hit.KILL) 14 else 10
            else -> if (hit == Hit.KILL) 8 else 4
        }
    }
}

/** One Woodsman arrow, recorded in order. Stored as its letter (W / K / M). */
enum class WoodsmanArrow(val letter: Char, val displayName: String) {
    WOUND('W', "Wound"),
    KILL('K', "Kill"),
    MISS('M', "Miss");

    companion object {
        fun fromLetter(c: Char): WoodsmanArrow? = entries.firstOrNull { it.letter == c }
    }
}

/**
 * Woodsman: one animal, shoot until you kill it, up to 3 arrows.
 *  Wound 5 on any arrow; kill 30 / 20 / 10 on arrow 1 / 2 / 3 (and shooting stops); miss 0.
 *  e.g. kill 1st = 30; wound then kill = 25; miss, wound, kill = 15; three wounds = 15.
 */
object WoodsmanScoring {
    const val MAX_ARROWS = 3
    const val MAX_PER_TARGET = 30
    const val WOUND = 5

    fun killPoints(arrowNo: Int): Int = MAX_PER_TARGET - (arrowNo - 1) * 10

    fun points(arrows: List<WoodsmanArrow>): Int = arrows.mapIndexed { i, a ->
        when (a) {
            WoodsmanArrow.WOUND -> WOUND
            WoodsmanArrow.KILL -> killPoints(i + 1)
            WoodsmanArrow.MISS -> 0
        }
    }.sum()

    /** Up to 3 arrows, nothing after a kill. */
    fun isValid(arrows: List<WoodsmanArrow>): Boolean =
        arrows.size <= MAX_ARROWS && arrows.dropLast(1).none { it == WoodsmanArrow.KILL }

    /** Finished: killed, or all 3 arrows shot. */
    fun isFinished(arrows: List<WoodsmanArrow>): Boolean =
        arrows.lastOrNull() == WoodsmanArrow.KILL || arrows.size == MAX_ARROWS

    fun encode(arrows: List<WoodsmanArrow>): String = arrows.joinToString("") { it.letter.toString() }

    fun decode(s: String?): List<WoodsmanArrow> = s.orEmpty().mapNotNull { WoodsmanArrow.fromLetter(it) }
}

/** The result for one animal on a target. */
sealed interface AnimalResult {
    val points: Int

    /** Not shot / not entered yet. */
    data object Pending : AnimalResult {
        override val points = 0
    }

    /** All available arrows used without scoring (or no arrows were left for it). */
    data object Missed : AnimalResult {
        override val points = 0
    }

    data class Scored(val arrowNo: Int, val hit: Hit) : AnimalResult {
        init {
            require(BigGameScoring.isValid(arrowNo, hit)) { "$hit is not valid on arrow $arrowNo" }
        }

        override val points: Int get() = BigGameScoring.points(arrowNo, hit)
    }
}

enum class TargetType(val displayName: String, val shortName: String) {
    /** One animal, up to 3 arrows. */
    STANDARD("Standard", "Std"),

    /**
     * Two animals sharing 3 arrows. Shoot the predator until it scores, then the prey with
     * whatever arrows are left. Each animal is scored by arrows shot at that animal. Max 48.
     */
    PREDATOR_PREY("Predator / Prey", "P/P"),

    /** Several animals on one peg, each with up to 3 arrows, normal scoring. Max 24 x animals. */
    FAN("Fan", "Fan"),

    /** Woodsman rounds: one animal, arrows recorded in order (see [WoodsmanScoring]). Max 30. */
    WOODSMAN("Woodsman", "Wds"),
}

/** The score for one target (peg) on the course. */
data class TargetScore(
    val targetNo: Int,
    val type: TargetType = TargetType.STANDARD,
    val animals: List<AnimalResult> = List(animalCountFor(type)) { AnimalResult.Pending },
    /** A manual score for non-standard scoring. When set, it replaces the calculated score. */
    val overridePoints: Int? = null,
    /** Woodsman targets: arrows in order. Empty for Big Game. */
    val arrows: List<WoodsmanArrow> = emptyList(),
) {
    init {
        require(targetNo >= 1) { "Target number must be 1 or more" }
        when (type) {
            TargetType.STANDARD -> require(animals.size == 1) { "Standard target has 1 animal" }
            TargetType.PREDATOR_PREY -> require(animals.size == 2) { "Predator/prey target has 2 animals" }
            TargetType.FAN -> require(animals.size in MIN_FAN..MAX_FAN) { "Fan has $MIN_FAN to $MAX_FAN animals" }
            TargetType.WOODSMAN -> {
                require(animals.isEmpty()) { "Woodsman targets record arrows, not animals" }
                require(WoodsmanScoring.isValid(arrows)) { "Up to 3 arrows, nothing after a kill" }
            }
        }
        require(type == TargetType.WOODSMAN || arrows.isEmpty()) { "Only Woodsman targets record arrows" }
        require(overridePoints == null || overridePoints >= 0) { "Override cannot be negative" }
        validateArrowLimits(type, animals)
    }

    val isWoodsman: Boolean get() = type == TargetType.WOODSMAN

    val calculatedPoints: Int get() = if (isWoodsman) WoodsmanScoring.points(arrows) else animals.sumOf { it.points }
    val totalPoints: Int get() = overridePoints ?: calculatedPoints
    val isOverridden: Boolean get() = overridePoints != null
    val maxPoints: Int get() = if (isWoodsman) WoodsmanScoring.MAX_PER_TARGET else animals.size * BigGameScoring.MAX_PER_ANIMAL

    /** How many arrows can be shot at the given animal, given the other results. */
    fun arrowsAvailable(animalIndex: Int): Int = arrowsAvailable(type, animals, animalIndex)

    /** Fully scored: overridden, or every animal resolved (prey with no arrows left counts as resolved). */
    val isComplete: Boolean
        get() = isOverridden || (isWoodsman && WoodsmanScoring.isFinished(arrows)) || !isWoodsman && animals.indices.all { i ->
            val a = animals[i]
            a !is AnimalResult.Pending || (isPredatorResolved() && arrowsAvailable(i) == 0)
        }

    private fun isPredatorResolved() = animals.first() !is AnimalResult.Pending

    /** Label for each animal, e.g. "Predator", "Prey", "Animal 2". */
    fun animalLabel(index: Int): String = when (type) {
        TargetType.STANDARD -> "Animal"
        TargetType.PREDATOR_PREY -> if (index == 0) "Predator" else "Prey"
        TargetType.FAN -> "Animal ${index + 1}"
        TargetType.WOODSMAN -> "Animal"
    }

    /** Woodsman: records the next arrow (ignored once the target is finished). */
    fun withArrow(arrow: WoodsmanArrow): TargetScore =
        if (!isWoodsman || WoodsmanScoring.isFinished(arrows)) this else copy(arrows = arrows + arrow)

    /** Woodsman: takes back the last arrow. */
    fun withoutLastArrow(): TargetScore = if (arrows.isEmpty()) this else copy(arrows = arrows.dropLast(1))

    /**
     * Sets one animal's result. For predator/prey, if the predator changes so the prey's
     * result is no longer possible, the prey is reset to Pending.
     */
    fun withAnimalResult(index: Int, result: AnimalResult): TargetScore {
        require(index in animals.indices) { "No animal $index on this target" }
        if (result is AnimalResult.Scored) {
            require(result.arrowNo <= arrowsAvailable(index)) {
                "Only ${arrowsAvailable(index)} arrow(s) available for ${animalLabel(index)}"
            }
        }
        val updated = animals.toMutableList().also { it[index] = result }
        return copy(animals = fixDependents(type, updated))
    }

    /** Changes the target type, keeping whatever results still make sense. */
    fun withType(newType: TargetType, fanAnimals: Int = MIN_FAN): TargetScore {
        val count = if (newType == TargetType.FAN) fanAnimals else animalCountFor(newType)
        require(newType != TargetType.FAN || count in MIN_FAN..MAX_FAN) { "Fan has $MIN_FAN to $MAX_FAN animals" }
        val kept = List(count) { i -> animals.getOrElse(i) { AnimalResult.Pending } }
        return copy(type = newType, animals = fixDependents(newType, kept))
    }

    fun withOverride(points: Int?): TargetScore = copy(overridePoints = points)

    fun cleared(): TargetScore = copy(animals = List(animals.size) { AnimalResult.Pending }, overridePoints = null, arrows = emptyList())

    companion object {
        const val MIN_FAN = 2
        const val MAX_FAN = 6

        fun animalCountFor(type: TargetType): Int = when (type) {
            TargetType.STANDARD -> 1
            TargetType.PREDATOR_PREY -> 2
            TargetType.FAN -> MIN_FAN
            TargetType.WOODSMAN -> 0
        }

        fun arrowsAvailable(type: TargetType, animals: List<AnimalResult>, index: Int): Int {
            if (type != TargetType.PREDATOR_PREY || index == 0) return BigGameScoring.MAX_ARROWS
            return when (val predator = animals[0]) {
                is AnimalResult.Scored -> BigGameScoring.MAX_ARROWS - predator.arrowNo
                AnimalResult.Missed, AnimalResult.Pending -> 0
            }
        }

        private fun validateArrowLimits(type: TargetType, animals: List<AnimalResult>) {
            animals.forEachIndexed { i, a ->
                if (a is AnimalResult.Scored) {
                    require(a.arrowNo <= arrowsAvailable(type, animals, i)) {
                        "Animal ${i + 1} scored on arrow ${a.arrowNo} but only ${arrowsAvailable(type, animals, i)} available"
                    }
                }
            }
        }

        /** Resets the prey if the predator no longer leaves enough arrows for its result. */
        private fun fixDependents(type: TargetType, animals: List<AnimalResult>): List<AnimalResult> {
            if (type != TargetType.PREDATOR_PREY) return animals
            val prey = animals[1]
            val available = arrowsAvailable(type, animals, 1)
            val preyOk = when (prey) {
                is AnimalResult.Scored -> prey.arrowNo <= available
                AnimalResult.Missed -> animals[0] !is AnimalResult.Pending
                AnimalResult.Pending -> true
            }
            return if (preyOk) animals else listOf(animals[0], AnimalResult.Pending)
        }
    }
}

/** Short description of an animal's result for scorecards, e.g. "Inner kill, 1st arrow". */
fun AnimalResult.describe(): String = when (this) {
    AnimalResult.Pending -> "Not scored"
    AnimalResult.Missed -> "Miss"
    is AnimalResult.Scored -> "${hit.displayName}, ${ordinal(arrowNo)} arrow"
}

/** e.g. "Wound, Kill (2nd)", "Miss, Miss, Miss", "Not scored". */
fun List<WoodsmanArrow>.describe(): String =
    if (isEmpty()) "Not scored"
    else mapIndexed { i, a -> if (a == WoodsmanArrow.KILL) "Kill (${ordinal(i + 1)})" else a.displayName }.joinToString(", ")

fun ordinal(n: Int): String = when (n) {
    1 -> "1st"
    2 -> "2nd"
    3 -> "3rd"
    else -> "${n}th"
}
