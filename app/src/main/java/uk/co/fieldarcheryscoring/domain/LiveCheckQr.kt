package uk.co.fieldarcheryscoring.domain

/**
 * Checking scores by QR (2.8, Pete): the every-5-targets check with no signal and no phone in range can be done by
 * scanning the other scorer's QR instead of reading the points out. Compare only: nothing is copied across; the cards
 * whose entries match are marked checked by hand on the phone that scanned, as if "They agree" had been tapped.
 *
 * The same text in the Android app and the web app (web/src/domain/liveCheck.ts; keep the two in step):
 *
 *     fieldarchery:check:1:<group>:<scorer>:<entry>;<entry>;…
 *
 * group and scorer are the first 8 hex characters of their ids; each entry is `<target>.<card>.<score>` with card the
 * first 8 hex characters of the card's member id, and score the target's type letter (S standard, P predator / prey,
 * F fan, W Woodsman) then, for Big Game, each animal as `-` (not scored), `M` (miss) or arrow and hit (`1I` inner kill,
 * `1K`, `2W` …) joined with commas, for Woodsman the arrows (e.g. `WK`), and `=N` on the end when overridden.
 * E.g. `fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.S1K;7.9b1c22e0.P1K,M;8.9b1c22e0.W=12`.
 */
object LiveCheckQr {
    const val PREFIX = "fieldarchery:check:1:"

    /** The first 8 hex characters of an id (as in the QR). */
    fun short(id: String): String = id.replace("-", "").lowercase().take(8)

    data class Item(val targetNo: Int, val card: String, val target: TargetScore)
    data class Payload(val group: String, val scorer: String, val items: List<Item>)

    fun isCheckQr(text: String): Boolean = text.trim().startsWith(PREFIX)

    fun encode(groupId: String, scorerMemberId: String, entries: List<Pair<String, TargetScore>>): String =
        PREFIX + short(groupId) + ":" + short(scorerMemberId) + ":" +
            entries.sortedWith(compareBy({ it.second.targetNo }, { it.first }))
                .joinToString(";") { (card, t) -> "${t.targetNo}.${short(card)}.${encodeTarget(t)}" }

    fun encodeTarget(t: TargetScore): String {
        val body = when (t.type) {
            TargetType.WOODSMAN -> WoodsmanScoring.encode(t.arrows)
            TargetType.FUN -> ""
            else -> t.animals.joinToString(",") { a ->
                when (a) {
                    AnimalResult.Pending -> "-"
                    AnimalResult.Missed -> "M"
                    is AnimalResult.Scored -> "${a.arrowNo}" + when (a.hit) { Hit.INNER_KILL -> "I"; Hit.KILL -> "K"; Hit.WOUND -> "W" }
                }
            }
        }
        val type = when (t.type) { TargetType.STANDARD -> "S"; TargetType.PREDATOR_PREY -> "P"; TargetType.FAN -> "F"; TargetType.WOODSMAN -> "W"; TargetType.FUN -> "X" }
        return type + body + (t.overridePoints?.let { "=$it" } ?: "")
    }

    /** Null when the text isn't a check QR or doesn't read cleanly. */
    fun decode(text: String): Payload? = runCatching {
        val s = text.trim()
        if (!s.startsWith(PREFIX)) return null
        val parts = s.removePrefix(PREFIX).split(":", limit = 3)
        if (parts.size != 3 || parts[0].length != 8 || parts[1].length != 8) return null
        val items = if (parts[2].isEmpty()) emptyList() else parts[2].split(";").map { e ->
            val f = e.split(".")
            require(f.size == 3 && f[1].length == 8)
            val n = f[0].toInt()
            Item(n, f[1], decodeTarget(n, f[2])!!)
        }
        Payload(parts[0], parts[1], items)
    }.getOrNull()

    fun decodeTarget(targetNo: Int, code: String): TargetScore? = runCatching {
        val eq = code.indexOf('=')
        val override = if (eq >= 0) code.substring(eq + 1).toInt() else null
        val main = if (eq >= 0) code.substring(0, eq) else code
        val type = when (main.first()) {
            'S' -> TargetType.STANDARD; 'P' -> TargetType.PREDATOR_PREY; 'F' -> TargetType.FAN; 'W' -> TargetType.WOODSMAN; 'X' -> TargetType.FUN
            else -> return null
        }
        val body = main.drop(1)
        if (type == TargetType.FUN) {
            require(body.isEmpty())
            TargetScore(targetNo, type, emptyList(), override)
        } else if (type == TargetType.WOODSMAN) {
            TargetScore(targetNo, type, emptyList(), override, WoodsmanScoring.decode(body).also { require(it.size == body.length) })
        } else {
            val animals = body.split(",").map { a ->
                when {
                    a == "-" -> AnimalResult.Pending
                    a == "M" -> AnimalResult.Missed
                    a.length == 2 -> AnimalResult.Scored(
                        a[0].digitToInt(),
                        when (a[1]) { 'I' -> Hit.INNER_KILL; 'K' -> Hit.KILL; 'W' -> Hit.WOUND; else -> error("hit") },
                    )
                    else -> error("animal")
                }
            }
            TargetScore(targetNo, type, animals, override)
        }
    }.getOrNull()

    /** One card on one target where the two scorers differ. */
    data class Difference(val targetNo: Int, val cardMemberId: String, val mine: TargetScore, val theirs: TargetScore)

    data class Result(
        /** (target, card) whose two entries match: marked checked on this phone. */
        val matched: Set<Pair<Int, String>>,
        val differences: List<Difference>,
        /** (target, card) this phone wanted checked that the other phone hasn't entered (or wasn't in the QR). */
        val notInTheirs: List<Pair<Int, String>>,
    )

    /**
     * Compares this phone's entries waiting for a check ([mine]: (target, card id) → score) with a scanned [theirs].
     * Cards are matched on the first 8 characters of their ids; what has to match is [LiveRules.matchKey].
     */
    fun compare(mine: Map<Pair<Int, String>, TargetScore>, theirs: Payload): Result {
        val byKey = theirs.items.associateBy { it.targetNo to it.card }
        val matched = mutableSetOf<Pair<Int, String>>()
        val differences = mutableListOf<Difference>()
        val missing = mutableListOf<Pair<Int, String>>()
        for ((key, t) in mine.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val other = byKey[key.first to short(key.second)]?.target
            when {
                other == null || !other.isComplete -> missing += key
                LiveRules.matchKey(other) == LiveRules.matchKey(t) -> matched += key
                else -> differences += Difference(key.first, key.second, t, other)
            }
        }
        return Result(matched, differences, missing)
    }

    /**
     * Why a scanned QR can't be used here, or null when it can: another group's, this phone's own, or from someone who
     * doesn't score any of the cards waiting here. [scorersOf]: card id → its scorers' member ids.
     */
    fun problem(
        payload: Payload,
        groupId: String,
        myMemberId: String,
        cards: Collection<String>,
        scorersOf: (String) -> List<String>,
    ): String? = when {
        payload.group != short(groupId) -> "That QR is from a different group."
        payload.scorer == short(myMemberId) -> "That's this phone's own QR. Scan the other scorer's phone."
        cards.none { card -> scorersOf(card).any { short(it) == payload.scorer } } ->
            "That QR is from someone who doesn't score these cards. Scan the other scorer of them."
        else -> null
    }
}
