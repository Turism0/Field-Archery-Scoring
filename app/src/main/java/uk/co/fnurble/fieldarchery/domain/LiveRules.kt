package uk.co.fnurble.fieldarchery.domain

import java.time.Instant

/**
 * Live group rules on the phone (app 2.3): the same as the server's GroupRules.cs and its Cells, so the host's phone
 * can share out scorers and every phone can check double / 3+ scorers' entries with no signal. Keep the two in step.
 */
object LiveRules {
    const val SINGLE = "SINGLE"
    const val DOUBLE = "DOUBLE"
    const val MULTI = "MULTI"
    const val SELF = "SELF"

    const val HOST = "HOST"
    const val JOINED = "JOINED"
    const val FRIEND = "FRIEND"
    const val GUEST = "GUEST"

    const val OPEN = "OPEN"
    const val STARTED = "STARTED"
    const val CLOSED = "CLOSED"

    const val WAITING = "WAITING"
    const val MATCHED = "MATCHED"
    const val MISMATCH = "MISMATCH"

    /** Manual checks (2.3): this many targets a scorer has entered that haven't been compared with the other scorer. */
    const val MANUAL_CHECK_TARGETS = 5

    fun hasPhone(kind: String) = kind == HOST || kind == JOINED

    /** OPEN < STARTED < CLOSED: a group only moves forward, whichever way a phone hears about it. */
    fun statusRank(status: String) = when (status) { CLOSED -> 2; STARTED -> 1; else -> 0 }

    fun laterStatus(a: String, b: String) = if (statusRank(b) > statusRank(a)) b else a

    data class Member(val memberId: String, val kind: String, val sortOrder: Int)

    /**
     * The usual scorers for a method, as card → scorers (server GroupRules.Default). SINGLE: the host scores everyone.
     * DOUBLE: the host and the first joined archer. MULTI: each phone archer's card goes to the next two phone archers
     * round the group; other cards to the two scorers with fewest cards. SELF: phone archers score themselves, the host
     * the rest. Returns problems instead when the method can't work with these members.
     */
    fun defaultScorers(method: String, members: List<Member>): Pair<Map<String, List<String>>, List<String>> {
        val ordered = members.sortedBy { it.sortOrder }
        val phones = ordered.filter { hasPhone(it.kind) }
        val host = ordered.firstOrNull { it.kind == HOST } ?: return emptyMap<String, List<String>>() to listOf("The group has no host.")
        val out = linkedMapOf<String, MutableList<String>>()
        fun add(card: String, scorer: String) { out.getOrPut(card) { mutableListOf() }.add(scorer) }
        when (method) {
            SINGLE -> ordered.forEach { add(it.memberId, host.memberId) }
            DOUBLE -> {
                val second = phones.firstOrNull { it.kind == JOINED }
                    ?: return emptyMap<String, List<String>>() to listOf("Double scoring needs a second archer to join on their own phone.")
                ordered.forEach { add(it.memberId, host.memberId); add(it.memberId, second.memberId) }
            }
            MULTI -> {
                if (phones.size < 3) return emptyMap<String, List<String>>() to listOf("Three or more scorers need at least three archers on their own phones.")
                val load = phones.associate { it.memberId to 0 }.toMutableMap()
                for (i in phones.indices) {
                    for (s in listOf(phones[(i + 1) % phones.size], phones[(i + 2) % phones.size])) {
                        add(phones[i].memberId, s.memberId)
                        load[s.memberId] = load.getValue(s.memberId) + 1
                    }
                }
                for (c in ordered.filter { !hasPhone(it.kind) }) {
                    for (s in phones.sortedWith(compareBy<Member> { load.getValue(it.memberId) }.thenBy { it.sortOrder }).take(2)) {
                        add(c.memberId, s.memberId)
                        load[s.memberId] = load.getValue(s.memberId) + 1
                    }
                }
            }
            SELF -> ordered.forEach { add(it.memberId, if (hasPhone(it.kind)) it.memberId else host.memberId) }
            else -> return emptyMap<String, List<String>>() to listOf("Unknown scoring method.")
        }
        return out to emptyList()
    }

    /** Checks scorers against the method's rules (server GroupRules.Validate); empty when they're fine. */
    fun validate(method: String, members: List<Member>, scorers: Map<String, List<String>>): List<String> {
        val byId = members.associateBy { it.memberId }
        for ((card, list) in scorers) {
            if (card !in byId) return listOf("A scorer is set for someone who isn't in the group.")
            for (s in list) {
                val scorer = byId[s] ?: return listOf("A scorer isn't in the group.")
                if (!hasPhone(scorer.kind)) return listOf("Only archers with the group on their own phone can score.")
            }
            if (list.distinct().size != list.size) return listOf("The same scorer is listed twice for one card.")
        }
        val perCard = scorers.filterValues { it.isNotEmpty() }
        if (members.any { it.memberId !in perCard }) return listOf("Every card needs a scorer.")
        val needed = if (method == DOUBLE || method == MULTI) 2 else 1
        if (perCard.values.any { it.size != needed }) {
            return listOf(if (needed == 2) "Every card needs two different scorers." else "Every card needs one scorer.")
        }
        val problems = mutableListOf<String>()
        when (method) {
            SINGLE, DOUBLE -> {
                val first = perCard.values.first().sorted()
                if (perCard.values.any { it.sorted() != first }) {
                    problems += if (method == SINGLE) "One scorer scores every card." else "The same two scorers score every card."
                }
            }
            MULTI -> {
                if (perCard.any { (card, list) -> card in list }) problems += "Nobody scores their own card."
                val loads = perCard.values.flatten().groupingBy { it }.eachCount().values
                if (loads.size < 3) problems += "Three or more scorers are needed."
                else if (loads.max() - loads.min() > 1) problems += "Cards must be shared out as evenly as possible."
            }
            SELF -> members.filter { hasPhone(it.kind) }.forEach {
                if (perCard.getValue(it.memberId).first() != it.memberId) {
                    problems += "In self scoring, archers on their own phones score themselves."
                }
            }
        }
        return problems.distinct()
    }

    /** What has to match for two entries to be the same score (server GroupRules.MatchKey: points could hide a different hit). */
    fun matchKey(t: TargetScore): String = listOf(
        t.type.name,
        t.overridePoints?.toString() ?: "-",
        t.totalPoints.toString(),
        if (t.type == TargetType.WOODSMAN) WoodsmanScoring.encode(t.arrows) else "",
        t.animals.mapIndexed { i, a ->
            val state = when (a) { AnimalResult.Pending -> "PENDING"; AnimalResult.Missed -> "MISSED"; is AnimalResult.Scored -> "SCORED" }
            "$i:$state:${(a as? AnimalResult.Scored)?.arrowNo ?: ""}:${(a as? AnimalResult.Scored)?.hit?.name ?: ""}:${a.points}"
        }.joinToString(";"),
    ).joinToString("|")

    /** One scorer's entry for one card on one target. */
    data class Entry(
        val targetNo: Int,
        val cardMemberId: String,
        val scorerMemberId: String,
        val target: TargetScore,
        val enteredUtc: Instant,
    ) {
        val key: Triple<Int, String, String> get() = Triple(targetNo, cardMemberId, scorerMemberId)
    }

    /**
     * Each card's score on each target (server Cells): MATCHED when every scorer for the card has entered the same score
     * (one scorer: as soon as it's entered), MISMATCH when two differ, WAITING while a scorer still has to enter it.
     * Entries from someone not scoring the card are ignored; before scorers are set any single entry counts.
     */
    fun cells(entries: Collection<Entry>, scorers: Map<String, List<String>>): Map<Pair<Int, String>, String> =
        entries.groupBy { it.targetNo to it.cardMemberId }.mapValues { (key, list) ->
            val assigned = scorers[key.second].orEmpty()
            val counted = if (assigned.isEmpty()) list.take(1) else list.filter { it.scorerMemberId in assigned }
            val needed = maxOf(1, assigned.size)
            when {
                counted.size < needed -> WAITING
                counted.map { matchKey(it.target) }.distinct().size == 1 -> MATCHED
                else -> MISMATCH
            }
        }

    /** Newest wins (by when it was entered on its phone); the same entry heard twice changes nothing. */
    fun mergeEntries(
        mine: Map<Triple<Int, String, String>, Entry>,
        incoming: Collection<Entry>,
    ): Pair<Map<Triple<Int, String, String>, Entry>, List<Entry>> {
        val out = mine.toMutableMap()
        val changed = mutableListOf<Entry>()
        for (e in incoming) {
            val have = out[e.key]
            if (have == null || have.enteredUtc < e.enteredUtc) {
                out[e.key] = e
                changed += e
            }
        }
        return out to changed
    }

    /**
     * Manual checks (2.3, Pete): targets this scorer has entered for cards they share with another scorer, where the
     * other scorer's entry hasn't arrived (no signal and no phone in range), and that haven't been checked by hand.
     * When there are [MANUAL_CHECK_TARGETS] or more, the oldest five are due a check: the scorers compare them aloud.
     */
    fun dueManualCheck(
        myMemberId: String,
        entries: Collection<Entry>,
        scorers: Map<String, List<String>>,
        manuallyChecked: Set<Pair<Int, String>>,
    ): List<Int> {
        val cells = cells(entries, scorers)
        val unchecked = entries
            .filter { it.scorerMemberId == myMemberId && scorers[it.cardMemberId].orEmpty().size >= 2 }
            .filter { cells[it.targetNo to it.cardMemberId] == WAITING && (it.targetNo to it.cardMemberId) !in manuallyChecked }
            .filter { it.target.isComplete }
            .map { it.targetNo }
            .distinct()
            .sorted()
        return if (unchecked.size >= MANUAL_CHECK_TARGETS) unchecked.take(MANUAL_CHECK_TARGETS) else emptyList()
    }
}
