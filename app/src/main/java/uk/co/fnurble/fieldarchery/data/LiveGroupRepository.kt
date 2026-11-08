package uk.co.fnurble.fieldarchery.data

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import uk.co.fnurble.fieldarchery.data.local.GroupRole
import uk.co.fnurble.fieldarchery.data.local.RoundEntity
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.AnimalResult
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.Hit
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.domain.TargetScore
import uk.co.fnurble.fieldarchery.domain.TargetType
import uk.co.fnurble.fieldarchery.domain.WoodsmanScoring
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * How a live group is scored. [checked] methods have two scorers per card: a score is only saved when both have entered
 * the same thing (2.1.01).
 */
enum class LiveMethod(val code: String, val displayName: String, val description: String, val checked: Boolean = false) {
    SINGLE("SINGLE", "One scorer", "You score everyone; the others watch on their own phones."),
    DOUBLE(
        "DOUBLE", "Double scoring",
        "You and the first archer to join both enter every score; each is saved when your two match.", checked = true,
    ),
    MULTI(
        "MULTI", "Three or more scorers",
        "Everyone on their own phone scores two others (never themselves), shared out evenly; each score is saved when " +
            "its two scorers match. Needs 3+ archers on their own phones.",
        checked = true,
    ),
    SELF("SELF", "Self scoring", "Everyone scores their own card on their own phone; you score friends and guests you added."),
    ;

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code }
    }
}

/** Someone in a live group, as the server sees it. [memberId] is also the id of their round on this phone. */
data class LiveMember(
    val memberId: String,
    /** HOST, JOINED (own phone), FRIEND (added by the host), GUEST. */
    val kind: String,
    val archerId: String?,
    val name: String,
    val archerClass: ArcherClass,
    val ageGroup: AgeGroup,
    val bowStyle: BowStyle,
    val sortOrder: Int,
    /** Member ids of whoever scores this card (empty until the group starts). */
    val scorers: List<String>,
) {
    val hasPhone: Boolean get() = kind == "HOST" || kind == "JOINED"
}

/** One scorer's entry for one card on one target. */
data class LiveEntry(val targetNo: Int, val cardMemberId: String, val scorerMemberId: String, val target: TargetScore, val points: Int)

/** A live group as this phone last heard it from the server. */
data class LiveGroupState(
    val groupId: String,
    val code: String,
    val method: String,
    val roundType: RoundType,
    val targetCount: Int,
    val shootDate: LocalDate,
    val locationId: String?,
    val locationName: String?,
    /** OPEN (waiting to start), STARTED, CLOSED. */
    val status: String,
    val myMemberId: String,
    val myKind: String,
    val members: List<LiveMember>,
    /** Every scorer's entries (double / 3+ scorers: two per card and target). */
    val entries: List<LiveEntry> = emptyList(),
    /** WAITING / MATCHED / MISMATCH by (target, card), for cards with at least one entry. */
    val cells: Map<Pair<Int, String>, String> = emptyMap(),
) {
    val isHost: Boolean get() = myKind == "HOST"
    /** Two scorers per card, saved when they match. */
    val checked: Boolean get() = LiveMethod.fromCode(method)?.checked == true
    val started: Boolean get() = status == "STARTED"
    val closed: Boolean get() = status == "CLOSED"

    fun member(id: String) = members.firstOrNull { it.memberId == id }

    /** Can this phone score [cardMemberId]'s card? */
    fun canScore(cardMemberId: String) = started && member(cardMemberId)?.scorers?.contains(myMemberId) == true

    /** Names of whoever scores a card, for "Dave is scoring this card". */
    fun scorerNames(cardMemberId: String): List<String> =
        member(cardMemberId)?.scorers.orEmpty().mapNotNull { id -> member(id)?.let { if (it.memberId == myMemberId) "You" else it.name } }

    /**
     * Double / 3+ scorers: where a card's score on a target has got to ("Waiting for Dave", "Doesn't match…", "Saved").
     * [iEntered]: this phone has entered it (it may not have reached the server yet).
     */
    fun checkText(targetNo: Int, cardMemberId: String, iEntered: Boolean): String? {
        if (!checked || !started) return null
        val scorers = member(cardMemberId)?.scorers.orEmpty()
        if (scorers.isEmpty()) return null
        val entered = entries.filter { it.targetNo == targetNo && it.cardMemberId == cardMemberId && it.scorerMemberId in scorers }
        fun name(id: String) = if (id == myMemberId) "you" else member(id)?.name ?: "?"
        return when (cells[targetNo to cardMemberId]) {
            "MATCHED" -> "Both scores match: saved."
            "MISMATCH" -> {
                val points = entered.joinToString(", ") { "${name(it.scorerMemberId)} ${it.points}" }
                val sameTotal = entered.map { it.points }.distinct().size == 1
                "Scores don't match ($points" + (if (sameTotal) ", same points but different hits" else "") +
                    "). Check with each other and re-enter."
            }
            else -> {
                val missing = scorers.filter { id -> entered.none { it.scorerMemberId == id } && !(id == myMemberId && iEntered) }
                if (missing.isEmpty()) "Checking…"
                else "Waiting for " + missing.joinToString(" and ") { name(it) } + " to enter" +
                    (if (iEntered) " (yours is in)." else ".")
            }
        }.replaceFirstChar { it.uppercase() }
    }
}

/** One poll: the latest state (kept if a poll fails) and the problem, if the last poll failed. */
data class LivePoll(val state: LiveGroupState?, val problem: String? = null)

/**
 * Live groups (app 2.1; double and 3+ scorers 2.1.01): one round shot together, scored on several phones through the server.
 *
 * Every phone keeps a round for everyone in the group (the 2.0 group rounds): its own (SCORER), friends and guests the
 * host added (on the host's phone), and copies of other phones' cards (MIRROR). Scores go to the server as entries;
 * every few seconds each phone fetches the group and copies saved scores into its rounds, so the group strip, scorecard
 * and history all work as in 2.0. Needs signal (no-signal scoring comes later).
 */
class LiveGroupRepository(
    context: Context,
    private val api: FieldArcheryApi,
    private val accounts: AccountStore,
    private val rounds: RoundRepository,
) {
    private val prefs = context.getSharedPreferences("live_groups", Context.MODE_PRIVATE)
    private val sendLock = Mutex()
    /**
     * This phone's own entries by group then (target, card). For double / 3+ scorers these are what the scoring screen
     * shows for cards this phone scores until they're matched (the round only gets matched scores).
     */
    private val _mine = MutableStateFlow<Map<String, Map<Pair<Int, String>, TargetScore>>>(emptyMap())

    fun myEntries(groupId: String): Flow<Map<Pair<Int, String>, TargetScore>> = _mine.map { it[groupId].orEmpty() }

    /** Scores not yet accepted by the server, by group then (target, card): sent again on the next poll. */
    private val pending = mutableMapOf<String, MutableMap<Pair<Int, String>, JSONObject>>()

    /** True if [groupId] is a live group on this phone. */
    fun isLive(groupId: String?): Boolean = groupId != null && prefs.contains(groupId)

    /** This phone's member id in a live group. */
    fun myMemberId(groupId: String): String? = prefs.getString(groupId, null)

    // ---- Starting and joining ----------------------------------------------------------------------------

    /**
     * Host: puts the group (already on this phone, made by [RoundRepository.createGroupRound] with [groupId]) on the
     * server with a new join code. Returns the code.
     */
    suspend fun create(groupId: String, method: LiveMethod): String = call { token ->
        val local = rounds.groupRounds(groupId)
        val mine = local.first { it.groupRole == GroupRole.SCORER }
        prefs.edit().putString(groupId, mine.id).apply()
        var lastError: ApiException? = null
        repeat(5) {
            val code = newCode()
            try {
                api.putGroup(token, groupId, groupJson(code, method.code, "OPEN", local))
                prefs.edit().putString("$groupId:code", code).putString("$groupId:method", method.code).apply()
                return@call code
            } catch (e: ApiException) {
                if (e.code != "code_taken") {
                    prefs.edit().remove(groupId).apply()
                    throw e
                }
                lastError = e
            }
        }
        prefs.edit().remove(groupId).apply()
        throw lastError ?: ApiException(409, "code_taken", "Couldn't find a free code. Try again.")
    }

    /** Host: starts scoring (the server works out who scores which card). */
    suspend fun start(groupId: String) = setStatus(groupId, "STARTED")

    /** Host: closes the group (the code stops working; other phones finish their rounds). */
    suspend fun close(groupId: String) = setStatus(groupId, "CLOSED")

    private suspend fun setStatus(groupId: String, status: String) = call { token ->
        val code = prefs.getString("$groupId:code", null) ?: throw ApiException(404, "no_such_group", "That group isn't on this phone.")
        val method = prefs.getString("$groupId:method", LiveMethod.SINGLE.code)!!
        api.putGroup(token, groupId, groupJson(code, method, status, rounds.groupRounds(groupId)))
        Unit
    }

    /**
     * Join a group with its code. Makes this archer's round and copies of everyone else's on this phone. Returns the
     * group id.
     */
    suspend fun join(code: String, archerClass: ArcherClass, ageGroup: AgeGroup, bowStyle: BowStyle): String = call { token ->
        val memberId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("memberId", memberId)
            .put("archerClass", if (archerClass == ArcherClass.LADIES) "L" else "G")
            .put("ageGroup", ageGroup.name)
            .put("bowStyle", bowStyle.code)
            .put("roundId", memberId)
        val json = api.joinGroup(token, body)
        val groupId = json.getString("groupId")
        // Joining again (e.g. after reinstalling): the server keeps our first member id.
        val mine = json.getJSONObject("me").getString("memberId")
        prefs.edit().putString(groupId, mine).apply()
        apply(parse(json))
        groupId
    }

    /** Leave a group you joined (your round stays on your phone). */
    suspend fun leave(groupId: String) = call { token ->
        myMemberId(groupId)?.let { api.removeGroupMember(token, groupId, it) }
        prefs.edit().remove(groupId).apply()
    }

    // ---- Following the group -----------------------------------------------------------------------------

    /** Fetches the group every few seconds while collected, copying saved scores into this phone's rounds. */
    fun poll(groupId: String, everyMillis: Long = 3_000): Flow<LivePoll> = flow {
        var last: LiveGroupState? = null
        while (true) {
            val result = try {
                flush(groupId)
                val state = call { token -> parse(api.group(token, groupId)) }
                apply(state)
                last = state
                LivePoll(state)
            } catch (e: ApiException) {
                LivePoll(last, e.message)
            }
            emit(result)
            if (result.state?.closed == true) break
            delay(everyMillis)
        }
    }

    /** Sends a score this phone entered for [cardMemberId] (kept and sent again if there's no signal). */
    suspend fun send(groupId: String, cardMemberId: String, target: TargetScore) {
        val me = myMemberId(groupId) ?: return
        val entry = JSONObject()
            .put("targetNo", target.targetNo)
            .put("cardMemberId", cardMemberId)
            .put("scorerMemberId", me)
            .put("target", target.toJson())
            .put("enteredUtc", Instant.now().toString())
        _mine.update { all -> all + (groupId to (all[groupId].orEmpty() + ((target.targetNo to cardMemberId) to target))) }
        sendLock.withLock { pending.getOrPut(groupId) { mutableMapOf() }[target.targetNo to cardMemberId] = entry }
        runCatching { flush(groupId) }
    }

    private suspend fun flush(groupId: String) = sendLock.withLock {
        val waiting = pending[groupId]?.takeIf { it.isNotEmpty() } ?: return@withLock
        val batch = JSONArray().apply { waiting.values.forEach { put(it) } }
        val sent = waiting.keys.toList()
        call { token -> api.putEntries(token, groupId, batch) }
        sent.forEach { waiting.remove(it) }
    }

    /**
     * Brings this phone's rounds in line with the server: a round for every member (copies of other phones' cards),
     * friends who joined on their own phone become copies (their round is theirs, so no request is sent), and every
     * saved score for a card this phone doesn't score is copied in. A closed group finishes everyone's round here.
     */
    private suspend fun apply(s: LiveGroupState) {
        val local = rounds.groupRounds(s.groupId).associateBy { it.id }
        for (m in s.members) {
            val isMe = m.memberId == s.myMemberId
            val role = when {
                isMe -> GroupRole.SCORER
                m.kind == "FRIEND" && s.isHost -> GroupRole.FRIEND
                m.kind == "GUEST" && s.isHost -> GroupRole.GUEST
                else -> GroupRole.MIRROR
            }
            val existing = local[m.memberId]
            if (existing == null) {
                rounds.addGroupRound(
                    s.groupId, m.memberId, role, if (isMe) null else m.name, m.sortOrder, s.shootDate, s.targetCount,
                    s.roundType, m.archerClass, m.ageGroup, m.bowStyle, s.locationId, s.locationName,
                )
            } else if (existing.groupRole != role || existing.groupOrder != m.sortOrder ||
                (!isMe && existing.groupName != m.name)
            ) {
                rounds.setGroupMember(m.memberId, role, if (isMe) null else m.name, m.sortOrder)
            }
        }
        // This phone's entries already on the server (e.g. after the app restarted): keep any newer one entered here.
        val fromServer = s.entries.filter { it.scorerMemberId == s.myMemberId }.associate { (it.targetNo to it.cardMemberId) to it.target }
        if (fromServer.isNotEmpty()) _mine.update { all -> all + (s.groupId to (fromServer + all[s.groupId].orEmpty())) }
        // Saved scores: for cards someone else scores, and (double / 3+ scorers) every card once both scorers match.
        val waiting = sendLock.withLock { pending[s.groupId]?.keys?.toSet().orEmpty() }
        for ((key, target) in savedTargets(s)) {
            val (targetNo, card) = key
            if ((!s.checked && s.canScore(card)) || key in waiting) continue   // this phone's own entries win
            val current = rounds.get(card) ?: continue
            if (current.target(targetNo) != target) rounds.saveTarget(card, target)
        }
        if (s.closed) {
            val all = rounds.groupRounds(s.groupId)
            // Cancelled before anyone scored: nothing worth keeping.
            if (all.none { (rounds.get(it.id)?.targetsComplete ?: 0) > 0 }) rounds.deleteGroup(s.myMemberId)
            else all.filter { it.status != RoundStatus.COMPLETED }.forEach { rounds.setStatus(it.id, RoundStatus.COMPLETED) }
            prefs.edit().remove(s.groupId).apply()
        }
    }

    /** The saved (matched) score for each target and card, from the entries. */
    private val lastEntries = mutableMapOf<String, JSONObject>()

    private fun savedTargets(s: LiveGroupState): Map<Pair<Int, String>, TargetScore> {
        val json = lastEntries[s.groupId] ?: return emptyMap()
        val matched = json.getJSONArray("cells").objects()
            .filter { it.getString("state") == "MATCHED" }
            .map { it.getInt("targetNo") to it.getString("cardMemberId") }
            .toSet()
        val byCell = json.getJSONArray("entries").objects()
            .groupBy { it.getInt("targetNo") to it.getString("cardMemberId") }
        return matched.mapNotNull { key ->
            val scorers = s.member(key.second)?.scorers.orEmpty()
            val entry = byCell[key].orEmpty()
                .filter { scorers.isEmpty() || it.getString("scorerMemberId") in scorers }
                .maxByOrNull { it.getString("enteredUtc") } ?: return@mapNotNull null
            runCatching { key to entry.getJSONObject("target").toTargetScore() }.getOrNull()
        }.toMap()
    }

    private fun parse(json: JSONObject): LiveGroupState {
        val groupId = json.getString("groupId")
        lastEntries[groupId] = json
        val me = json.getJSONObject("me")
        return LiveGroupState(
            groupId = groupId,
            code = json.getString("code"),
            method = json.getString("method"),
            roundType = RoundType.entries.firstOrNull { it.name == json.optString("roundType") } ?: RoundType.BIG_GAME,
            targetCount = json.getInt("targetCount"),
            shootDate = LocalDate.parse(json.getString("shootDate").take(10)),
            locationId = if (json.isNull("locationId")) null else json.optString("locationId"),
            locationName = if (json.isNull("locationName")) null else json.optString("locationName"),
            status = json.getString("status"),
            myMemberId = me.getString("memberId"),
            myKind = me.getString("kind"),
            members = json.getJSONArray("members").objects().map { m ->
                LiveMember(
                    memberId = m.getString("memberId"),
                    kind = m.getString("kind"),
                    archerId = if (m.isNull("archerId")) null else m.optString("archerId"),
                    name = if (m.isNull("name")) "?" else m.getString("name"),
                    archerClass = if (m.getString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS,
                    ageGroup = AgeGroup.valueOf(m.getString("ageGroup")),
                    bowStyle = BowStyle.fromCode(m.getString("bowStyle")) ?: BowStyle.entries.first(),
                    sortOrder = m.getInt("sortOrder"),
                    scorers = m.optJSONArray("scorers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                )
            },
            entries = json.optJSONArray("entries")?.objects().orEmpty().mapNotNull { e ->
                runCatching {
                    LiveEntry(
                        targetNo = e.getInt("targetNo"),
                        cardMemberId = e.getString("cardMemberId"),
                        scorerMemberId = e.getString("scorerMemberId"),
                        target = e.getJSONObject("target").toTargetScore(),
                        points = e.getInt("points"),
                    )
                }.getOrNull()
            },
            cells = json.optJSONArray("cells")?.objects().orEmpty()
                .associate { (it.getInt("targetNo") to it.getString("cardMemberId")) to it.getString("state") },
        )
    }

    /** The host's view of the group for the server: you, friends added by name and guests (joined archers add themselves). */
    private fun groupJson(code: String, method: String, status: String, local: List<RoundEntity>): JSONObject {
        val mine = local.first { it.groupRole == GroupRole.SCORER }
        val members = JSONArray()
        for (r in local) {
            val kind = when (r.groupRole) {
                GroupRole.SCORER -> "HOST"
                GroupRole.FRIEND -> "FRIEND"
                GroupRole.GUEST -> "GUEST"
                else -> continue   // joined archers aren't the host's to send
            }
            members.put(
                JSONObject()
                    .put("memberId", r.id)
                    .put("kind", kind)
                    .put("archerId", when (kind) { "HOST" -> accounts.account.value?.archerId; "FRIEND" -> r.groupArcherId; else -> null } ?: JSONObject.NULL)
                    .put("guestName", if (kind == "GUEST") r.groupName else JSONObject.NULL)
                    .put("archerClass", if (r.archerClass == ArcherClass.LADIES) "L" else "G")
                    .put("ageGroup", r.ageGroup.name)
                    .put("bowStyle", r.bowStyle.code)
                    .put("roundId", r.id),
            )
        }
        return JSONObject()
            .put("code", code)
            .put("method", method)
            .put("roundType", mine.roundType ?: "BIG_GAME")
            .put("targetCount", mine.targetCount)
            .put("shootDate", mine.shootDate.toString())
            .put("locationId", mine.locationId ?: JSONObject.NULL)
            .put("status", status)
            .put("members", members)
    }

    private suspend fun <T> call(block: suspend (String) -> T): T {
        val token = accounts.account.value?.token
            ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to use live groups.")
        try {
            return block(token)
        } catch (e: ApiException) {
            if (e.status == 401) accounts.clear()
            throw e
        }
    }

    companion object {
        /** Join codes: 6 characters, no 0/O, 1/I/L (easy to read out). Same as the server's. */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun newCode(): String = (1..6).map { CODE_ALPHABET.random() }.joinToString("")

        /** A code typed or scanned: the last 6 code characters in it (a QR holds "fieldarchery:join:CODE"). */
        fun cleanCode(text: String): String = text.uppercase().filter { it in CODE_ALPHABET }.takeLast(6)

        /** What the join QR code holds. */
        fun qrText(code: String) = "fieldarchery:join:$code"
    }
}

// ---- One target as the server's JSON (same shape as in a round upload) ----

internal fun TargetScore.toJson(): JSONObject {
    val animalArray = JSONArray()
    animals.forEachIndexed { i, a ->
        animalArray.put(
            JSONObject()
                .put("index", i)
                .put("state", when (a) { AnimalResult.Pending -> "PENDING"; AnimalResult.Missed -> "MISSED"; is AnimalResult.Scored -> "SCORED" })
                .put("arrowNo", (a as? AnimalResult.Scored)?.arrowNo ?: JSONObject.NULL)
                .put("hit", (a as? AnimalResult.Scored)?.hit?.name ?: JSONObject.NULL)
                .put("points", a.points),
        )
    }
    return JSONObject()
        .put("targetNo", targetNo)
        .put("type", type.name)
        .put("overridePoints", overridePoints ?: JSONObject.NULL)
        .put("points", totalPoints)
        .put("animals", animalArray)
        .apply { if (type == TargetType.WOODSMAN) put("arrows", WoodsmanScoring.encode(arrows)) }
}

internal fun JSONObject.toTargetScore(): TargetScore {
    val type = TargetType.valueOf(getString("type"))
    val animals = getJSONArray("animals").objects().sortedBy { it.getInt("index") }.map { a ->
        when (a.getString("state")) {
            "SCORED" -> AnimalResult.Scored(a.getInt("arrowNo"), Hit.valueOf(a.getString("hit")))
            "MISSED" -> AnimalResult.Missed
            else -> AnimalResult.Pending
        }
    }
    return TargetScore(
        targetNo = getInt("targetNo"),
        type = type,
        animals = animals,
        overridePoints = if (isNull("overridePoints")) null else getInt("overridePoints"),
        arrows = if (type == TargetType.WOODSMAN) WoodsmanScoring.decode(optString("arrows")) else emptyList(),
    )
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
