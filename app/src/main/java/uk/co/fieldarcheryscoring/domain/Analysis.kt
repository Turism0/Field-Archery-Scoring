package uk.co.fieldarcheryscoring.domain

import kotlin.math.sqrt

/*
 * Stats analysis (3.2.17, Pete): how you score (arrows, hits, target types), consistency and form, start and finish,
 * by location and by weather, from your own finished rounds. The same as the web app's domain/analysis.ts; tested in
 * AnalysisTest.kt. Averages are per animal (Big Game, out of 24) or per target (Woodsman, out of 30); fun shots (3.1)
 * are left out of everything.
 */

/** How many animals (Woodsman: targets) scored, or were killed for Woodsman, on arrow 1, 2 or 3, and how many not at all. */
data class ArrowCounts(val first: Int, val second: Int, val third: Int, val none: Int, val total: Int)

/** Big Game: the scoring hit on each recorded animal, or a miss. Woodsman (per target): killed, wounded only, or nothing. */
data class HitCounts(val innerKill: Int, val kill: Int, val wound: Int, val miss: Int, val total: Int)

data class HitPeriod(val label: String, val hits: HitCounts)

data class TypeAverage(val type: TargetType, val targets: Int, val animals: Int, val average: Double)

/** The average of the round averages and how far rounds usually are from it (standard deviation). */
data class Consistency(val rounds: Int, val average: Double, val spread: Double)

enum class FormDirection { UP, DOWN, STEADY }

/** Your last 5 rounds' average against the rounds before them. */
data class Form(val recent: Double, val before: Double, val change: Double, val direction: FormDirection)

/** Rounds in a row at or above your average: the run you're on now and the longest. */
data class Streak(val average: Double, val current: Int, val best: Int)

/** Average per animal in the first, middle and last third of each round, in shooting order. */
data class Thirds(val start: Double?, val middle: Double?, val finish: Double?)

data class LocationSummary(val name: String, val rounds: Int, val average: Double, val best: Double, val bestRound: Round)

/** One bow or set of arrows (3.2.24): its rounds, average, best and how often it kills (Big Game). */
data class EquipmentSummary(val id: String, val rounds: Int, val average: Double, val best: Double, val bestRound: Round, val killRate: Double?)

data class SplitSide(val rounds: Int, val average: Double?)

/** Two sides of a weather comparison. */
data class WeatherSplit(val label: String, val yesLabel: String, val noLabel: String, val yes: SplitSide, val no: SplitSide)

object Analysis {
    private val shotOrder = compareBy<Round>({ it.shootDate }, { it.createdAtMillis }, { it.id })

    /** Animals (Woodsman: targets) whose arrows were recorded: fully scored, not overridden, not fun shots. */
    private fun recorded(r: Round) = r.targets.filter { !it.isFun && it.isComplete && !it.isOverridden }

    fun arrowCounts(rounds: List<Round>): ArrowCounts {
        var first = 0; var second = 0; var third = 0; var none = 0; var total = 0
        fun add(arrow: Int?) {
            total++
            when (arrow) { 1 -> first++; 2 -> second++; 3 -> third++; else -> none++ }
        }
        for (r in rounds) for (t in recorded(r)) {
            if (r.type == RoundType.WOODSMAN) add(t.arrows.indexOf(WoodsmanArrow.KILL).takeIf { it >= 0 }?.plus(1))
            else t.animals.forEach { a -> add((a as? AnimalResult.Scored)?.arrowNo) }
        }
        return ArrowCounts(first, second, third, none, total)
    }

    fun hitCounts(rounds: List<Round>): HitCounts {
        var inner = 0; var kill = 0; var wound = 0; var miss = 0; var total = 0
        for (r in rounds) for (t in recorded(r)) {
            if (r.type == RoundType.WOODSMAN) {
                total++
                when {
                    WoodsmanArrow.KILL in t.arrows -> kill++
                    WoodsmanArrow.WOUND in t.arrows -> wound++
                    else -> miss++
                }
            } else for (a in t.animals) {
                total++
                when {
                    a !is AnimalResult.Scored -> miss++
                    a.hit == Hit.INNER_KILL -> inner++
                    a.hit == Hit.KILL -> kill++
                    else -> wound++
                }
            }
        }
        return HitCounts(inner, kill, wound, miss, total)
    }

    /** Your most recent [n] rounds. */
    fun recentRounds(rounds: List<Round>, n: Int): List<Round> = rounds.sortedWith(shotOrder).takeLast(n)

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Hits over time, oldest first: by year when the rounds cover two or more years, otherwise by month (last [max]). */
    fun hitsOverTime(rounds: List<Round>, max: Int = 8): List<HitPeriod> {
        val byYear = rounds.map { it.shootDate.year }.distinct().size >= 2
        fun key(r: Round) = if (byYear) "%04d".format(r.shootDate.year) else "%04d-%02d".format(r.shootDate.year, r.shootDate.monthValue)
        return rounds.map(::key).distinct().sorted().takeLast(max).map { k ->
            HitPeriod(
                if (byYear) k else "${months[k.substring(5, 7).toInt() - 1]} ${k.substring(0, 4)}",
                hitCounts(rounds.filter { key(it) == k }),
            )
        }.filter { it.hits.total > 0 }
    }

    /** Average per animal on standard targets, predator/prey and fans (fully scored targets, overrides included). */
    fun byTargetType(rounds: List<Round>): List<TypeAverage> =
        listOf(TargetType.STANDARD, TargetType.PREDATOR_PREY, TargetType.FAN).mapNotNull { type ->
            val ts = rounds.filter { it.type == RoundType.BIG_GAME }.flatMap { it.targets }.filter { it.type == type && it.isComplete }
            val animals = ts.sumOf { it.animals.size }
            if (animals == 0) null else TypeAverage(type, ts.size, animals, ts.sumOf { it.totalPoints }.toDouble() / animals)
        }

    /** Each round's average per animal, oldest first (rounds with nothing scored left out). */
    fun roundAverages(rounds: List<Round>): List<Pair<Round, Double>> =
        rounds.sortedWith(shotOrder).mapNotNull { r -> r.stats.averagePerAnimal?.let { r to it } }

    fun consistency(rounds: List<Round>): Consistency? {
        val xs = roundAverages(rounds).map { it.second }
        if (xs.size < 3) return null
        val mean = xs.average()
        val spread = sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
        return Consistency(xs.size, mean, spread)
    }

    /** UP or DOWN when it's moved by 2% of the maximum per animal or more, otherwise STEADY. Needs [n] + 1 rounds. */
    fun form(rounds: List<Round>, type: RoundType, n: Int = 5): Form? {
        val xs = roundAverages(rounds).map { it.second }
        if (xs.size < n + 1) return null
        val recent = xs.takeLast(n).average()
        val before = xs.dropLast(n).average()
        val change = recent - before
        val step = (if (type == RoundType.WOODSMAN) 30 else 24) * 0.02
        val dir = when { change >= step -> FormDirection.UP; change <= -step -> FormDirection.DOWN; else -> FormDirection.STEADY }
        return Form(recent, before, change, dir)
    }

    fun streak(rounds: List<Round>): Streak? {
        val xs = roundAverages(rounds).map { it.second }
        if (xs.size < 3) return null
        val mean = xs.average()
        var current = 0; var best = 0
        for (x in xs) { current = if (x >= mean) current + 1 else 0; best = maxOf(best, current) }
        return Streak(mean, current, best)
    }

    /** Target numbers in the order they were shot: from the starting target round to the one before it. */
    fun shootingOrder(r: Round): List<Int> {
        val n = r.targetCount
        val s = r.startTarget?.takeIf { it in 1..n } ?: 1
        return (0 until n).map { (s - 1 + it) % n + 1 }
    }

    fun thirds(rounds: List<Round>): Thirds {
        val pts = IntArray(3); val animals = IntArray(3)
        for (r in rounds) {
            val order = shootingOrder(r).map { r.target(it) }.filterNot { it.isFun }
            order.forEachIndexed { i, t ->
                if (!t.isComplete) return@forEachIndexed
                val third = minOf(2, i * 3 / order.size)
                pts[third] += t.totalPoints
                animals[third] += if (r.type == RoundType.WOODSMAN) 1 else t.animals.size
            }
        }
        fun a(i: Int) = if (animals[i] == 0) null else pts[i].toDouble() / animals[i]
        return Thirds(a(0), a(1), a(2))
    }

    /** Your rounds at each named location: how many, average and best average per animal. Most-shot first. */
    fun byLocation(rounds: List<Round>): List<LocationSummary> =
        rounds.filter { !it.location.name.isNullOrBlank() }
            .groupBy { it.location.name!!.trim().lowercase() }
            .values.mapNotNull { list ->
                val avgs = roundAverages(list)
                if (avgs.isEmpty()) return@mapNotNull null
                val best = avgs.maxBy { it.second }
                LocationSummary(list.first().location.name!!.trim(), avgs.size, avgs.map { it.second }.average(), best.second, best.first)
            }
            .sortedWith(compareBy<LocationSummary>({ -it.rounds }, { -it.average }, { it.name }))

    /**
     * 3.2.29 (Pete): Stats compares each bow + arrows pair actually shot ("bowId|arrowId", either part empty when not
     * picked), so arrows used with two bows aren't averaged together. Null: neither picked.
     */
    fun equipmentPair(r: Round): String? =
        if (r.bowId == null && r.arrowId == null) null else "${r.bowId.orEmpty()}|${r.arrowId.orEmpty()}"

    /** Each bow's rounds ([key] = Round::bowId), arrows' (Round::arrowId) or pair's ([equipmentPair]), most rounds first. */
    fun byEquipment(rounds: List<Round>, key: (Round) -> String?): List<EquipmentSummary> =
        rounds.filter { key(it) != null }.groupBy { key(it)!! }.mapNotNull { (id, list) ->
            val avgs = roundAverages(list)
            if (avgs.isEmpty()) return@mapNotNull null
            val best = avgs.maxBy { it.second }
            val hits = hitCounts(list.filter { it.type == RoundType.BIG_GAME })
            val kills = if (hits.total == 0) null else (hits.innerKill + hits.kill).toDouble() / hits.total
            EquipmentSummary(id, avgs.size, avgs.map { it.second }.average(), best.second, best.first, kills)
        }.sortedWith(compareBy<EquipmentSummary>({ -it.rounds }, { -it.average }, { it.id }))

    fun weatherSplits(rounds: List<Round>, weatherOf: (Round) -> Weather?): List<WeatherSplit> {
        val withWeather = roundAverages(rounds).mapNotNull { (r, avg) -> weatherOf(r)?.let { Triple(r, avg, it) } }
        fun side(l: List<Triple<Round, Double, Weather>>) = SplitSide(l.size, if (l.isEmpty()) null else l.map { it.second }.average())
        fun split(label: String, yes: String, no: String, f: (Weather) -> Boolean): WeatherSplit {
            val (y, n) = withWeather.partition { f(it.third) }
            return WeatherSplit(label, yes, no, side(y), side(n))
        }
        return listOf(
            split("Rain", "Wet", "Dry", WeatherRules::isWet),
            split("Wind", "Windy (15+ mph)", "Calmer", WeatherRules::isWindy),
            split("Temperature", "Cold (under 8°C)", "Milder", WeatherRules::isCold),
        )
    }

    /** "Better than 70% of Gents Adult Longbow rounds", or null with too few rounds to say. */
    fun comparedText(betterThan: Double?, rounds: Int, who: String): String? =
        if (betterThan == null || rounds < 20) null else "Better than ${Math.round(betterThan * 100)}% of $who rounds"
}
