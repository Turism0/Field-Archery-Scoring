package uk.co.fnurble.fieldarchery.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import uk.co.fnurble.fieldarchery.data.local.GroupRole
import uk.co.fnurble.fieldarchery.data.remote.ApiException
import uk.co.fnurble.fieldarchery.data.remote.FieldArcheryApi
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.AnimalResult
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.Hit
import uk.co.fnurble.fieldarchery.domain.LiveRules
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.RoundType
import uk.co.fnurble.fieldarchery.domain.TargetScore
import uk.co.fnurble.fieldarchery.domain.TargetType
import uk.co.fnurble.fieldarchery.domain.WoodsmanScoring
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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

/** Someone in a live group. [memberId] is also the id of their round on this phone. */
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
data class LiveEntry(
    val targetNo: Int,
    val cardMemberId: String,
    val scorerMemberId: String,
    val target: TargetScore,
    val points: Int,
    val enteredUtc: Instant = Instant.EPOCH,
)

/** A live group as this phone has it (from the server, the host's phone, or both). */
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
    /** WAITING / MATCHED / MISMATCH by (target, card), for cards with at least one entry. Worked out on this phone. */
    val cells: Map<Pair<Int, String>, String> = emptyMap(),
    /** (target, card) this phone's scorer checked by hand with the other scorer (no signal, 2.3). */
    val manualChecks: Set<Pair<Int, String>> = emptySet(),
    /** How many archers the host said would be in the group; 0 if not said (2.3.01). */
    val expected: Int = 0,
    /** Hosts who were taken over from (2.3.01). */
    val formerHosts: Set<String> = emptySet(),
) {
    val isHost: Boolean get() = myKind == "HOST"
    /** Everyone the host expects is in (or no number was given). */
    val everyoneIn: Boolean get() = members.size >= expected
    /** A joined archer can take over if the host's phone has gone (2.3.01). */
    val canTakeOver: Boolean get() = !isHost && myKind == "JOINED" && !closed
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

    /** The other scorers of the cards this phone scores (for "Check with Sue"). */
    fun otherScorerNames(cards: Collection<String>): List<String> =
        cards.flatMap { member(it)?.scorers.orEmpty() }.distinct().filter { it != myMemberId }.mapNotNull { member(it)?.name }

    /**
     * Double / 3+ scorers: targets due a check by hand (2.3): five this phone has entered whose other scorer's entries
     * haven't arrived. Empty when none are due.
     */
    fun dueManualCheck(): List<Int> {
        if (!checked || !started) return emptyList()
        val list = entries.map { LiveRules.Entry(it.targetNo, it.cardMemberId, it.scorerMemberId, it.target, it.enteredUtc) }
        return LiveRules.dueManualCheck(myMemberId, list, members.associate { it.memberId to it.scorers }, manualChecks)
    }

    /** This phone's own entries waiting for the other scorer on [targets]: (target, card) → points. */
    fun myWaiting(targets: Collection<Int>): Map<Pair<Int, String>, Int> = entries
        .filter { it.scorerMemberId == myMemberId && it.targetNo in targets && cells[it.targetNo to it.cardMemberId] == LiveRules.WAITING }
        .filter { (it.targetNo to it.cardMemberId) !in manualChecks }
        .associate { (it.targetNo to it.cardMemberId) to it.points }

    /**
     * Double / 3+ scorers: where a card's score on a target has got to ("Waiting for Dave", "Doesn't match…", "Saved").
     * [iEntered]: this phone has entered it.
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
                when {
                    missing.isEmpty() -> "Checking…"
                    (targetNo to cardMemberId) in manualChecks ->
                        "Checked by hand and saved here. It's compared with " + missing.joinToString(" and ") { name(it) } +
                            "'s when their phone is back in range."
                    else -> "Waiting for " + missing.joinToString(" and ") { name(it) } + " to enter" +
                        (if (iEntered) " (yours is in)." else ".")
                }
            }
        }.replaceFirstChar { it.uppercase() }
    }
}

/**
 * One look at the group: the latest state, the server's problem (null when it's answering), and phone to phone.
 */
data class LivePoll(
    val state: LiveGroupState?,
    val problem: String? = null,
    val nearby: NearbyStatus = NearbyStatus(),
) {
    /** Some way to the others is working: the server, or phone to phone (with the host's phone, or any for the host). */
    val inTouch: Boolean get() = problem == null || nearby.connected > 0

    /** How this phone is keeping in touch with the others, when it matters (no signal). Null when all's well. */
    fun linkNote(): String? {
        val s = state ?: return problem?.let { "Can't reach the server ($it). Looking for the host's phone…" }
        if (s.closed || problem == null) return null
        return if (s.isHost) {
            val others = s.members.count { it.hasPhone && it.memberId != s.myMemberId }
            when {
                others == 0 -> "No signal. Keep scoring: it's sent when there's signal."
                nearby.connected == 0 -> "No signal and no phones in range. Keep scoring: it all catches up when they're back in range."
                nearby.connected < others -> "No signal: sharing phone to phone with ${nearby.connected} of $others phones."
                else -> "No signal: sharing phone to phone."
            }
        } else {
            if (nearby.connected > 0) "No signal: sharing with the host's phone."
            else "Out of range of the host. Keep scoring: it all catches up when you're back in range."
        }
    }
}

/**
 * Live groups (app 2.1; double and 3+ scorers 2.1.01; no signal 2.3): one round shot together, scored on several phones.
 *
 * Every phone keeps the whole group (members, who scores which card, and every scorer's entries) in a file of its own,
 * and fills it from whichever way to the others is working, at the same time (2.3):
 * - **the server**, whenever the phone has signal (as in 2.1; a future web app would use only this);
 * - **phone to phone** ([NearbyLink], Bluetooth / Wi-Fi), with the host's phone as the hub.
 * Entries are merged (newest wins); who's in the group and who scores what is the host's phone's to decide (with the
 * server's rules, [LiveRules]), and a group only moves forward (open → started → closed). Matching is worked out on every
 * phone. A phone out of range of everyone keeps scoring and catches up when it's back.
 *
 * Every phone keeps a round for everyone in the group (the 2.0 group rounds): its own (SCORER), friends and guests the
 * host added (on the host's phone), and copies of other phones' cards (MIRROR), so the group strip, scorecard and history
 * work as in 2.0.
 */
class LiveGroupRepository(
    private val context: Context,
    private val api: FieldArcheryApi,
    private val accounts: AccountStore,
    private val rounds: RoundRepository,
) {
    private val prefs = context.getSharedPreferences("live_groups", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    /** One server conversation at a time. */
    private val syncLock = Mutex()
    private val nearby = NearbyLink(context)
    private val dir = File(context.filesDir, "live_groups").apply { mkdirs() }

    private val replicas = MutableStateFlow<Map<String, LiveReplica>>(emptyMap())
    /** The server's last problem with a group (null: answering). */
    private val serverProblem = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** What the server has, from its last answer: entries by key with when they were entered, and member ids. */
    private val serverSeen = mutableMapOf<String, MutableMap<EntryKey, Instant>>()
    private val serverMembers = mutableMapOf<String, Set<String>>()
    private val lastPut = mutableMapOf<String, String>()
    private val lastOneByOne = mutableMapOf<String, Long>()

    /** Members: when the host's phone last sent the group (it beats the server's copy for a while after). */
    private val lastFromHost = mutableMapOf<String, Long>()
    /** Host: which phone (endpoint) is which member. */
    private val endpointMember = mutableMapOf<String, String>()

    private class Session(var refs: Int, val job: Job, var stopper: Job? = null)
    private val sessions = mutableMapOf<String, Session>()
    private var nearbyGroup: String? = null

    private class PendingJoin(val code: String, val hello: JSONObject, val done: CompletableDeferred<JSONObject>)
    @Volatile private var pendingJoin: PendingJoin? = null

    /** Phone-to-phone messages, handled one at a time in the order they came. */
    private val incoming = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)

    private val listener = object : NearbyLink.Listener {
        override fun onConnected(endpointId: String) { incoming.trySend(endpointId to JSONObject().put("t", "_CONNECTED")) }
        override fun onDisconnected(endpointId: String) { incoming.trySend(endpointId to JSONObject().put("t", "_GONE")) }
        override fun onMessage(endpointId: String, message: JSONObject) { incoming.trySend(endpointId to message) }
    }

    init {
        scope.launch { for ((ep, msg) in incoming) runCatching { onNearby(ep, msg) }.onFailure { Log.w(TAG, "Nearby message", it) } }
        // Groups closed with no signal: tell the server when there is.
        scope.launch {
            while (isActive) {
                closing().forEach { gid -> runCatching { sendClose(gid) } }
                delay(30_000)
            }
        }
    }

    fun myEntries(groupId: String): Flow<Map<Pair<Int, String>, TargetScore>> = replicas.map { all ->
        val r = all[groupId] ?: return@map emptyMap()
        val me = myMemberId(groupId)
        r.entries.values.filter { it.scorerMemberId == me }.associate { (it.targetNo to it.cardMemberId) to it.target }
    }.distinctUntilChanged()

    /** True if [groupId] is a live group on this phone. */
    fun isLive(groupId: String?): Boolean = groupId != null && prefs.contains(groupId)

    /** This phone's member id in a live group. */
    fun myMemberId(groupId: String): String? = prefs.getString(groupId, null)

    private fun isHost(groupId: String) = prefs.getBoolean("$groupId:host", false)

    // ---- Starting and joining ----------------------------------------------------------------------------

    /**
     * Host: makes the live group from the rounds already on this phone (made by [RoundRepository.createGroupRound] with
     * [groupId]) with a new join code. [expected]: how many archers will be in the group (Start waits for them, 2.3.01).
     * Works with no signal: the server is told when there is. Returns the code.
     */
    suspend fun create(groupId: String, method: LiveMethod, expected: Int = 0): String {
        val account = accounts.account.value ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to use live groups.")
        val local = rounds.groupRounds(groupId)
        val mine = local.first { it.groupRole == GroupRole.SCORER }
        val members = local.filter { it.groupRole != GroupRole.MIRROR }.sortedBy { it.groupOrder ?: 0 }.mapIndexed { i, r ->
            val kind = when (r.groupRole) { GroupRole.SCORER -> LiveRules.HOST; GroupRole.FRIEND -> LiveRules.FRIEND; else -> LiveRules.GUEST }
            LiveMember(
                memberId = r.id, kind = kind,
                archerId = when (kind) { LiveRules.HOST -> account.archerId; LiveRules.FRIEND -> r.groupArcherId; else -> null },
                name = if (kind == LiveRules.HOST) account.username else r.groupName ?: "?",
                archerClass = r.archerClass, ageGroup = r.ageGroup, bowStyle = r.bowStyle, sortOrder = i, scorers = emptyList(),
            )
        }
        val code = newCode()
        val r = LiveReplica(
            groupId = groupId, code = code, method = method.code,
            roundType = RoundType.entries.firstOrNull { it.name == mine.roundType } ?: RoundType.BIG_GAME,
            targetCount = mine.targetCount, shootDate = mine.shootDate, locationId = mine.locationId,
            locationName = mine.locationName, status = LiveRules.OPEN, members = members, expected = expected,
        )
        prefs.edit().putString(groupId, mine.id).putString("$groupId:code", code).putString("$groupId:method", method.code)
            .putBoolean("$groupId:host", true)
            .putStringSet("$groupId:added", members.filter { it.kind != LiveRules.HOST }.map { it.memberId }.toSet()).apply()
        lock.withLock { put(r) }
        // With signal, the server checks the group now (and a clashing code is changed). Without, carry on.
        try {
            syncLock.withLock { hostSync(groupId) }
        } catch (e: ApiException) {
            if (!e.isNetwork && e.status in 400..499) {
                forget(groupId)
                throw e
            }
        }
        return replicas.value[groupId]?.code ?: code
    }

    /** Host: starts scoring. Who scores which card is worked out here (the server's rules), so it works with no signal. */
    suspend fun start(groupId: String) {
        lock.withLock {
            val r = replicas.value[groupId] ?: throw ApiException(404, "no_such_group", "That group isn't on this phone.")
            val (scorers, problems) = LiveRules.defaultScorers(r.method, r.ruleMembers())
            if (problems.isNotEmpty()) throw ApiException(400, "invalid_scorers", problems.joinToString(" "))
            put(r.copy(status = LiveRules.STARTED, members = r.members.map { it.copy(scorers = scorers[it.memberId].orEmpty()) }))
            broadcastState(groupId)
        }
        scope.launch { runCatching { syncLock.withLock { hostSync(groupId) } } }
    }

    /** Host: closes the group (the code stops working; other phones finish their rounds). Works with no signal. */
    suspend fun close(groupId: String) {
        prefs.edit().putStringSet(CLOSING, closing() + groupId).commit()
        lock.withLock {
            val r = replicas.value[groupId] ?: return
            put(r.copy(status = LiveRules.CLOSED))
            broadcastState(groupId)
        }
        runCatching { sendClose(groupId) }
    }

    /**
     * Join a group with its code: through the server, or phone to phone from the host's phone, whichever answers first
     * (no signal is fine if the host's phone is in range). Makes this archer's round and copies of everyone else's on
     * this phone. Returns the group id.
     */
    suspend fun join(code: String, archerClass: ArcherClass, ageGroup: AgeGroup, bowStyle: BowStyle): String {
        val account = accounts.account.value ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to use live groups.")
        val clean = code.trim().uppercase()
        val memberId = UUID.randomUUID().toString()
        val me = LiveMember(memberId, LiveRules.JOINED, account.archerId, account.username, archerClass, ageGroup, bowStyle, 0, emptyList())
        val hello = JSONObject().put("t", "HELLO").put("v", PROTOCOL).put("code", clean).put("member", memberJson(me))
        val pending = PendingJoin(clean, hello, CompletableDeferred())
        pendingJoin = pending
        nearby.use(NearbyLink.Target.Member(null, clean, account.username), listener)
        var groupId: String? = null
        try {
            val deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS
            while (groupId == null && System.currentTimeMillis() < deadline) {
                val fromServer = try {
                    api.joinGroup(account.token, joinBody(clean, null, me))
                } catch (e: ApiException) {
                    if (e.status == 401) accounts.clear()
                    // The host on another phone (theirs died): this phone becomes the host's (2.3.01).
                    if (e.code == "is_host") { groupId = hostOnThisPhone(account.token, clean); break }
                    if (!e.isNetwork && e.code != "no_such_group") throw e
                    null
                }
                groupId = if (fromServer != null) joinedViaServer(fromServer)
                else withTimeoutOrNull(5_000) { pending.done.await() }?.let { joinedViaNearby(it) }
            }
            if (pending.done.isCompleted && groupId == null) groupId = joinedViaNearby(pending.done.await())
        } finally {
            pendingJoin = null
            if (groupId == null) { nearby.stop(); nearbyGroup = null }
        }
        return groupId ?: throw ApiException(
            404, "no_such_group",
            "Couldn't find the group. Check the code with the host. With no signal, stand near the host's phone " +
                "(both with Bluetooth on and Nearby devices allowed).",
        )
    }

    /** The host joining their own group on another phone (with signal): the group from the server, as its host. */
    private suspend fun hostOnThisPhone(token: String, code: String): String {
        val mine = call { api.groups(token) }.optJSONArray("groups")?.objects().orEmpty()
            .firstOrNull { it.optString("code") == code && it.optString("myKind") == LiveRules.HOST && it.optString("status") != LiveRules.CLOSED }
            ?: throw ApiException(404, "no_such_group", "Couldn't find your group on the server.")
        val gid = mine.getString("groupId")
        val json = call { api.group(token, gid) }
        prefs.edit().putString(gid, json.getJSONObject("me").getString("memberId")).putString("$gid:code", json.getString("code"))
            .putString("$gid:method", json.getString("method")).putBoolean("$gid:host", true).putBoolean("$gid:server", true).apply()
        lock.withLock {
            val r = parseGroup(json, replicas.value[gid])
            put(r.copy(entries = LiveRules.mergeEntries(r.entries, json.optJSONArray("entries")?.objects().orEmpty().mapNotNull { parseEntry(it) }).first))
            mergeServer(gid, json)
        }
        followNearby(gid)
        return gid
    }

    private suspend fun joinedViaServer(json: JSONObject): String {
        val gid = json.getString("groupId")
        val mine = json.getJSONObject("me").getString("memberId")   // the server keeps our first member id
        prefs.edit().putString(gid, mine).putString("$gid:code", json.getString("code"))
            .putString("$gid:method", json.getString("method")).putBoolean("$gid:server", true).apply()
        lock.withLock { mergeServer(gid, json) }
        followNearby(gid)
        return gid
    }

    private suspend fun joinedViaNearby(state: JSONObject): String {
        val gid = state.getString("groupId")
        val mine = state.getJSONObject("me").getString("memberId")
        prefs.edit().putString(gid, mine).putString("$gid:code", state.getString("code"))
            .putString("$gid:method", state.getString("method")).putBoolean("$gid:server", false).apply()
        lock.withLock {
            put(parseGroup(state, replicas.value[gid]))
            lastFromHost[gid] = System.currentTimeMillis()
        }
        followNearby(gid)
        // Say hello again as a member of the group: the host's phone sends everything it has.
        replicas.value[gid]?.members?.firstOrNull { it.memberId == mine }?.let { m ->
            nearby.send(nearby.endpoints, JSONObject().put("t", "HELLO").put("v", PROTOCOL).put("code", state.getString("code"))
                .put("groupId", gid).put("hostTerm", replicas.value[gid]?.hostTerm ?: 0).put("member", memberJson(m)))
        }
        return gid
    }

    /**
     * Leave a group you joined. Before scoring starts you're taken out of the group; once it's started your card stays
     * in it (2.3.01), so joining again with the code gets it back with every score so far. Works with no signal.
     */
    suspend fun leave(groupId: String) {
        val me = myMemberId(groupId)
        if (replicas.value[groupId]?.status == LiveRules.STARTED) {
            if (nearbyGroup == groupId) { nearby.stop(); nearbyGroup = null }
            forget(groupId)
            return
        }
        if (nearbyGroup == groupId) {
            nearby.send(nearby.endpoints, JSONObject().put("t", "LEAVE"))
            delay(300)
            nearby.stop()
            nearbyGroup = null
        }
        accounts.account.value?.token?.let { token ->
            if (me != null) runCatching { api.removeGroupMember(token, groupId, me) }
        }
        forget(groupId)
    }

    /** Double / 3+ scorers, no signal (2.3): these cards on these targets were checked by hand with the other scorer. */
    suspend fun markChecked(groupId: String, cells: Set<Pair<Int, String>>) = lock.withLock {
        val r = replicas.value[groupId] ?: return@withLock
        put(r.copy(manualChecks = r.manualChecks + cells))
    }

    /**
     * The host's phone has gone (2.3.01): this phone takes over as host. The old host stays in the group as a card the
     * others score (they get it back by joining again on a phone); scorers are shared out again. Works with no signal:
     * phones in range follow this one, and the server is told when there's signal. If two phones take over, the later
     * one wins.
     */
    suspend fun takeOver(groupId: String) {
        val account = accounts.account.value ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to use live groups.")
        val me = myMemberId(groupId) ?: return
        lock.withLock {
            val r = replicas.value[groupId] ?: throw ApiException(404, "no_such_group", "That group isn't on this phone.")
            if (r.status == LiveRules.CLOSED) throw ApiException(409, "group_closed", "This group has been closed.")
            val old = r.members.filter { it.kind == LiveRules.HOST && it.memberId != me }.map { it.memberId }.toSet()
            val members = r.members.map {
                when {
                    it.memberId == me -> it.copy(kind = LiveRules.HOST, archerId = account.archerId)
                    it.memberId in old -> it.copy(kind = LiveRules.FRIEND)
                    else -> it
                }
            }
            prefs.edit().putBoolean("$groupId:host", true).putBoolean("$groupId:takeover", true).apply()
            lastFromHost.remove(groupId)
            endpointMember.clear()
            put(rescore(r.copy(members = members, hostTerm = r.hostTerm + 1, formerHosts = r.formerHosts + old)))
        }
        followNearby(groupId)
        scope.launch { runCatching { syncLock.withLock { hostSync(groupId) } } }
    }

    /** Someone else is the host now (they took over): this phone carries on as a member. Call with [lock] held. */
    private fun stepDown(groupId: String, hostTerm: Int?) {
        prefs.edit().putBoolean("$groupId:host", false).putBoolean("$groupId:server", false).remove("$groupId:takeover").apply()
        endpointMember.clear()
        lastFromHost.remove(groupId)
        val r = replicas.value[groupId] ?: return
        val me = myMemberId(groupId)
        put(r.copy(
            hostTerm = maxOf(r.hostTerm, hostTerm ?: r.hostTerm),
            members = r.members.map { if (it.memberId == me) it.copy(kind = LiveRules.JOINED) else it },
        ))
        scope.launch { followNearby(groupId) }
    }

    // ---- Following the group -----------------------------------------------------------------------------

    /**
     * The group as it changes, while collected: keeps it in step with the server (every few seconds) and the other
     * phones (phone to phone), and copies saved scores into this phone's rounds. Ends once the group is closed.
     */
    fun poll(groupId: String): Flow<LivePoll> =
        combine(replicas.map { it[groupId] }, serverProblem.map { it[groupId] }, nearby.status) { r, problem, near ->
            LivePoll(r?.let { stateOf(it) }, problem, if (nearbyGroup == groupId) near else NearbyStatus(needsPermission = near.needsPermission, bluetoothOff = near.bluetoothOff, wifiOff = near.wifiOff))
        }
            .distinctUntilChanged()
            .transformWhile { emit(it); it.state?.closed != true }
            .onStart {
                // Opened in the app: keep it going in the background too (2.4), even if Stop was tapped before.
                prefs.edit().remove("$groupId:paused").apply()
                if (isLive(groupId)) LiveGroupService.start(context)
                acquire(groupId)
            }
            .onCompletion { release(groupId) }

    /** Sends a score this phone entered for [cardMemberId]: to the others now if it can, else when it can. */
    suspend fun send(groupId: String, cardMemberId: String, target: TargetScore) {
        val me = myMemberId(groupId) ?: return
        val e = LiveRules.Entry(target.targetNo, cardMemberId, me, target, Instant.now().truncatedTo(ChronoUnit.MILLIS))
        lock.withLock {
            val r = replicas.value[groupId] ?: return
            put(r.copy(entries = r.entries + (e.key to e)))
        }
        if (nearbyGroup == groupId) nearby.send(nearby.endpoints, entriesMessage(listOf(e)))
        scope.launch { runCatching { pushEntries(groupId) } }
    }

    // ---- In the background (2.4) -------------------------------------------------------------------------

    /** Live groups to keep going in the background: not closed, and not stopped from the notification. */
    fun backgroundGroupIds(): Set<String> = prefs.all.keys.filter { it.endsWith(":code") }.map { it.removeSuffix(":code") }
        .filter { isLive(it) && !prefs.getBoolean("$it:paused", false) }.toSet()

    /** The notification's Stop: no background running until the group is opened in the app again. */
    fun pauseBackground() {
        val e = prefs.edit()
        backgroundGroupIds().forEach { e.putBoolean("$it:paused", true) }
        e.apply()
    }

    /**
     * Run by [LiveGroupService]: keeps every live group on this phone going (server and phone to phone) until it's
     * closed or stopped, telling [onStatus] what to show in the notification.
     */
    suspend fun keepRunning(onStatus: (String) -> Unit) {
        val held = mutableSetOf<String>()
        try {
            while (true) {
                val live = backgroundGroupIds()
                (live - held).forEach { acquire(it); held += it }
                (held - live).forEach { release(it); held -= it }
                if (held.isEmpty()) break
                onStatus(held.mapNotNull { replicas.value[it] }.joinToString("\n") { backgroundText(it) }
                    .ifEmpty { "Keeping scores in step with the other phones." })
                delay(5_000)
            }
        } finally {
            held.forEach { release(it) }
        }
    }

    private fun backgroundText(r: LiveReplica): String {
        val serverOk = serverProblem.value[r.groupId] == null
        val near = if (nearbyGroup == r.groupId) nearby.status.value.connected else 0
        val others = r.members.count { it.hasPhone && it.memberId != myMemberId(r.groupId) }
        val how = when {
            serverOk && near > 0 -> "signal and $near phone${if (near == 1) "" else "s"} in range"
            serverOk -> "through the server"
            near > 0 -> "no signal, $near phone${if (near == 1) "" else "s"} in range"
            others == 0 -> "no signal: sent when there is"
            else -> "no signal and no phones in range: catches up later"
        }
        return "${r.code.chunked(3).joinToString(" ")} · ${if (r.status == LiveRules.STARTED) "scoring" else "waiting to start"} · $how"
    }

    private fun acquire(groupId: String) = synchronized(sessions) {
        val s = sessions[groupId]
        if (s != null) {
            s.refs++
            s.stopper?.cancel()
            s.stopper = null
        } else {
            sessions[groupId] = Session(1, scope.launch { runSession(groupId) })
        }
    }

    /** Keeps going a while after the last screen stops watching (moving between screens shouldn't drop the phones). */
    private fun release(groupId: String) = synchronized(sessions) {
        val s = sessions[groupId] ?: return
        s.refs--
        if (s.refs > 0) return
        s.stopper = scope.launch {
            delay(LINGER_MS)
            synchronized(sessions) {
                if (s.refs > 0) return@launch
                s.job.cancel()
                sessions.remove(groupId)
            }
            if (nearbyGroup == groupId) { nearby.stop(); nearbyGroup = null }
        }
    }

    private suspend fun runSession(groupId: String) = coroutineScope {
        lock.withLock { if (replicas.value[groupId] == null) load(groupId)?.let { put(it, save = false) } }
        // Saved scores into this phone's rounds whenever the group changes.
        launch {
            replicas.map { it[groupId] }.distinctUntilChanged().collectLatest { r ->
                if (r != null) { delay(250); runCatching { applyToRounds(stateOf(r)) }.onFailure { Log.w(TAG, "apply", it) } }
            }
        }
        while (isActive && isLive(groupId)) {
            val r = replicas.value[groupId]
            if (r != null && r.status != LiveRules.CLOSED) followNearby(groupId)
            try {
                syncLock.withLock { if (isHost(groupId)) hostSync(groupId) else memberSync(groupId) }
                serverProblem.update { it + (groupId to null) }
            } catch (e: ApiException) {
                serverProblem.update { it + (groupId to e.message) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live group sync", e)
            }
            delay(POLL_MS)
        }
    }

    // ---- The server ----------------------------------------------------------------------------------------

    /** Host: learn who joined through the server, then send the group as this phone has it, then everyone's entries. */
    private suspend fun hostSync(groupId: String) {
        val token = token()
        refreshLocation(groupId)
        // Took over as host (2.3.01): tell the server first.
        if (prefs.getBoolean("$groupId:takeover", false)) {
            try {
                call { api.takeOverGroup(token, groupId) }
                prefs.edit().remove("$groupId:takeover").apply()
            } catch (e: ApiException) {
                if (e.isNetwork || e.status >= 500) throw e
                if (e.status != 404) {   // 404: the server hasn't got the group yet (made with no signal): carry on
                    prefs.edit().remove("$groupId:takeover").apply()
                    lock.withLock { stepDown(groupId, null) }
                    throw e
                }
            }
        }
        val server = try {
            call { api.group(token, groupId) }
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            null
        }
        // Someone else took over as host on the server: carry on as a member.
        val account = accounts.account.value
        if (server != null && account != null && !prefs.getBoolean("$groupId:takeover", false) &&
            server.optString("hostArcherId").let { it.isNotEmpty() && !it.equals(account.archerId, ignoreCase = true) }
        ) {
            lock.withLock { stepDown(groupId, null); mergeServer(groupId, server) }
            return
        }
        if (server != null) lock.withLock { mergeServer(groupId, server) }
        val r = replicas.value[groupId] ?: return
        // Archers who left phone to phone are taken off the server too.
        for (id in r.removed.intersect(serverMembers[groupId].orEmpty())) {
            runCatching { call { api.removeGroupMember(token, groupId, id) } }
        }
        val serverJoined = server?.optJSONArray("members")?.objects().orEmpty()
            .filter { it.getString("kind") == LiveRules.JOINED && it.getString("memberId") !in r.removed }
        val hostManaged = r.members.filter { it.kind != LiveRules.JOINED }
        val predicted = hostManaged.map { it.memberId }.toSet() + serverJoined.map { it.getString("memberId") }
        val allThere = predicted == r.members.map { it.memberId }.toSet()
        val serverStatus = server?.optString("status")
        // Started on this phone, but the server can't take the scorers until every phone has joined it: tell it
        // "started" only if its own rules would work for the archers it has.
        val status = when {
            r.status != LiveRules.STARTED || allThere -> r.status
            serverStatus == LiveRules.STARTED -> LiveRules.STARTED
            else -> {
                val rm = hostManaged.map { LiveRules.Member(it.memberId, it.kind, it.sortOrder) } +
                    serverJoined.map { LiveRules.Member(it.getString("memberId"), LiveRules.JOINED, it.getInt("sortOrder") + 1000) }
                if (LiveRules.defaultScorers(r.method, rm).second.isEmpty()) LiveRules.STARTED else LiveRules.OPEN
            }
        }
        val body = putBody(r, status, allThere)
        val text = body.toString()
        val serverScorers = server?.optJSONArray("members")?.objects().orEmpty()
            .associate { it.getString("memberId") to it.optJSONArray("scorers").strings().sorted() }
        val wantScorers = r.members.associate { it.memberId to it.scorers.sorted() }
        val serverOrder = server?.optJSONArray("members")?.objects().orEmpty().sortedBy { it.getInt("sortOrder") }.map { it.getString("memberId") }
        val needed = server == null || text != lastPut[groupId] || serverStatus != status || server.optString("code") != r.code ||
            (allThere && (serverOrder != r.members.sortedBy { it.sortOrder }.map { it.memberId } ||
                (status == LiveRules.STARTED && serverScorers != wantScorers)))
        if (needed) {
            var tries = 0
            while (true) {
                try {
                    val json = call { api.putGroup(token, groupId, putBody(replicas.value[groupId]!!, status, allThere)) }
                    lastPut[groupId] = putBody(replicas.value[groupId]!!, status, allThere).toString()
                    lock.withLock { mergeServer(groupId, json) }
                    break
                } catch (e: ApiException) {
                    if (e.code == "code_taken" && tries++ < 5) {
                        // Made with no signal and someone else has the code: a new one (the lobby shows it).
                        lock.withLock {
                            val now = replicas.value[groupId] ?: return
                            val code = newCode()
                            prefs.edit().putString("$groupId:code", code).apply()
                            put(now.copy(code = code))
                            broadcastState(groupId)
                        }
                        followNearby(groupId)
                        continue
                    }
                    if (e.code == "group_closed") {
                        lock.withLock { replicas.value[groupId]?.let { put(it.copy(status = LiveRules.CLOSED)) } }
                        return
                    }
                    throw e
                }
            }
        }
        pushEntries(groupId)
    }

    /** Other phones: join on the server (once it has the group), follow it, and send this phone's entries. */
    private suspend fun memberSync(groupId: String) {
        val token = token()
        val r = replicas.value[groupId]
        val me = myMemberId(groupId) ?: return
        if (!prefs.getBoolean("$groupId:server", false)) {
            val mine = r?.members?.firstOrNull { it.memberId == me } ?: return
            val json = try {
                call { api.joinGroup(token, joinBody(r.code, groupId, mine)) }
            } catch (e: ApiException) {
                if (e.code == "no_such_group") return   // made with no signal: the host's phone hasn't told it yet
                throw e
            }
            prefs.edit().putBoolean("$groupId:server", true).apply()
            lock.withLock { mergeServer(groupId, json) }
        } else {
            val json = try {
                call { api.group(token, groupId) }
            } catch (e: ApiException) {
                if (e.status == 404) prefs.edit().putBoolean("$groupId:server", false).apply()
                throw e
            }
            lock.withLock { mergeServer(groupId, json) }
        }
        pushEntries(groupId)
    }

    /**
     * Sends entries the server doesn't have yet: this phone's own, and on the host's phone everyone's it heard phone to
     * phone. Ones the server won't take yet (that archer hasn't joined it) are tried again later.
     */
    private suspend fun pushEntries(groupId: String) {
        val token = accounts.account.value?.token ?: return
        val members = serverMembers[groupId] ?: return   // no answer from the server yet
        val me = myMemberId(groupId) ?: return
        val host = isHost(groupId)
        val seen = serverSeen[groupId].orEmpty()
        val r = replicas.value[groupId] ?: return
        val toSend = r.entries.values.filter { e ->
            (host || e.scorerMemberId == me) && e.cardMemberId in members && e.scorerMemberId in members &&
                (seen[e.key]?.let { it < e.enteredUtc } ?: true)
        }
        if (toSend.isEmpty()) return
        fun sent(list: List<LiveRules.Entry>) = synchronized(serverSeen) {
            val m = serverSeen.getOrPut(groupId) { mutableMapOf() }
            list.forEach { m[it.key] = it.enteredUtc }
        }
        try {
            toSend.chunked(200).forEach { batch ->
                call { api.putEntries(token, groupId, JSONArray().apply { batch.forEach { put(entryJson(it)) } }) }
                sent(batch)
            }
        } catch (e: ApiException) {
            if (e.status != 400) throw e
            // One the server won't take holds up the rest: send them one at a time (now and then).
            val now = System.currentTimeMillis()
            if (now - (lastOneByOne[groupId] ?: 0) < 30_000) return
            lastOneByOne[groupId] = now
            for (one in toSend.take(60)) {
                try {
                    call { api.putEntries(token, groupId, JSONArray().put(entryJson(one))) }
                    sent(listOf(one))
                } catch (e2: ApiException) {
                    if (e2.status != 400) throw e2
                }
            }
        }
    }

    /** Takes in the server's copy of the group (call with [lock] held). */
    private fun mergeServer(groupId: String, json: JSONObject) {
        val entries = json.optJSONArray("entries")?.objects().orEmpty().mapNotNull { parseEntry(it) }
        synchronized(serverSeen) {
            val m = serverSeen.getOrPut(groupId) { mutableMapOf() }
            entries.forEach { e -> if ((m[e.key] ?: Instant.EPOCH) < e.enteredUtc) m[e.key] = e.enteredUtc }
        }
        val serverList = json.optJSONArray("members")?.objects().orEmpty().map { parseMember(it) }
        serverMembers[groupId] = serverList.map { it.memberId }.toSet()
        val base = replicas.value[groupId]
        var r = when {
            base == null -> parseGroup(json, null)
            isHost(groupId) -> {
                // The host decides: add archers who joined through the server (and haven't left), keep the rest.
                val known = base.members.map { it.memberId }.toSet()
                val serverJoined = serverList.filter { it.kind == LiveRules.JOINED }.associateBy { it.memberId }
                val joined = serverJoined.values.filter { it.memberId !in known && it.memberId !in base.removed }
                // A friend the host added who joined on their own phone through the server.
                val updated = base.members.map { m ->
                    serverJoined[m.memberId]?.takeIf { m.kind == LiveRules.FRIEND }?.let { m.copy(kind = LiveRules.JOINED) } ?: m
                }
                val withJoined = base.copy(
                    members = updated + joined.mapIndexed { i, m -> m.copy(sortOrder = base.members.size + i, scorers = emptyList()) },
                )
                rescore(withJoined.copy(status = LiveRules.laterStatus(base.status, json.optString("status", LiveRules.OPEN))))
            }
            // Others: the host's phone's copy wins while it's in range; otherwise the server's.
            System.currentTimeMillis() - (lastFromHost[groupId] ?: 0) < HOST_FRESH_MS ->
                base.copy(status = LiveRules.laterStatus(base.status, json.optString("status", LiveRules.OPEN)))
            else -> parseGroup(json, base)
        }
        val (merged, changed) = LiveRules.mergeEntries(r.entries, entries)
        r = r.copy(entries = merged)
        val structureChanged = base == null || base.copy(entries = emptyMap()) != r.copy(entries = emptyMap())
        if (base != r) put(r)
        // Pass on what came from the server to the phones in range (the host to everyone, others to the host).
        if (nearbyGroup == groupId) {
            if (changed.isNotEmpty()) sendEntries(nearby.endpoints, changed)
            if (structureChanged && isHost(groupId)) broadcastState(groupId)
        }
    }

    private suspend fun sendClose(groupId: String) {
        val r = replicas.value[groupId] ?: load(groupId)
        if (r == null) {
            prefs.edit().putStringSet(CLOSING, closing() - groupId).apply()
            return
        }
        val token = accounts.account.value?.token ?: return
        try {
            call { api.putGroup(token, groupId, putBody(r.copy(status = LiveRules.CLOSED), LiveRules.CLOSED, false)) }
        } catch (e: ApiException) {
            if (e.isNetwork || e.status >= 500) throw e
            // Anything else (e.g. never reached the server): nothing more to do.
        }
        prefs.edit().putStringSet(CLOSING, closing() - groupId).apply()
        if (!isLive(groupId)) File(dir, "$groupId.json").delete()
    }

    private fun closing(): Set<String> = prefs.getStringSet(CLOSING, emptySet()).orEmpty().toSet()

    /** The host's round got its shoot location (found after the group was made): pass it on. */
    private suspend fun refreshLocation(groupId: String) {
        val r = replicas.value[groupId] ?: return
        val mine = rounds.groupRounds(groupId).firstOrNull { it.groupRole == GroupRole.SCORER } ?: return
        if (mine.locationId != r.locationId || mine.locationName != r.locationName) {
            lock.withLock {
                replicas.value[groupId]?.let { put(it.copy(locationId = mine.locationId, locationName = mine.locationName)) }
                broadcastState(groupId)
            }
        }
    }

    // ---- Phone to phone ------------------------------------------------------------------------------------

    /** Points phone to phone at this group (the host advertises it; others look for the host's phone). */
    private fun followNearby(groupId: String) {
        val r = replicas.value[groupId] ?: return
        val name = accounts.account.value?.username ?: "Archer"
        val target = if (isHost(groupId)) NearbyLink.Target.Host(groupId, r.code) else NearbyLink.Target.Member(groupId, r.code, name)
        nearbyGroup = groupId
        nearby.use(target, listener)
    }

    private suspend fun onNearby(endpointId: String, msg: JSONObject) {
        val type = msg.optString("t")
        // Joining: the host's phone answers with the group (or says no).
        pendingJoin?.let { p ->
            when (type) {
                "_CONNECTED" -> { nearby.send(endpointId, p.hello); return }
                "STATE" -> { if (!p.done.isCompleted) p.done.complete(msg.getJSONObject("group")); return }
                "NO" -> { if (!p.done.isCompleted) p.done.completeExceptionally(ApiException(409, "refused", msg.optString("message"))); return }
            }
        }
        val gid = nearbyGroup ?: return
        if (isHost(gid)) hostMessage(gid, endpointId, type, msg) else memberMessage(gid, endpointId, type, msg)
    }

    private suspend fun hostMessage(gid: String, ep: String, type: String, msg: JSONObject) = lock.withLock {
        val r = replicas.value[gid] ?: return@withLock
        when (type) {
            "HELLO" -> {
                // That phone has heard from a newer host (someone took over): this one steps down.
                if (msg.optInt("hostTerm", 0) > r.hostTerm) {
                    stepDown(gid, msg.optInt("hostTerm"))
                    return@withLock
                }
                val m = parseMember(msg.getJSONObject("member"))
                val problem = when {
                    msg.optString("code") != r.code && msg.optString("groupId") != gid -> "That isn't this group's code."
                    r.status == LiveRules.CLOSED -> "This group has been closed."
                    m.archerId != null && r.members.any { it.kind == LiveRules.HOST && it.archerId == m.archerId } -> "You're the host of this group."
                    else -> null
                }
                if (problem != null) {
                    nearby.send(ep, JSONObject().put("t", "NO").put("message", problem))
                    return@withLock
                }
                // The same archer again (or a friend the host added, now on their own phone) keeps their member id.
                val existing = r.members.firstOrNull { it.memberId == m.memberId }
                    ?: r.members.firstOrNull { m.archerId != null && it.archerId == m.archerId }
                val members = if (existing != null) {
                    r.members.map {
                        if (it.memberId != existing.memberId) it
                        else it.copy(kind = LiveRules.JOINED, name = m.name, archerClass = m.archerClass, ageGroup = m.ageGroup, bowStyle = m.bowStyle)
                    }
                } else {
                    r.members + m.copy(kind = LiveRules.JOINED, sortOrder = r.members.size, scorers = emptyList())
                }
                val memberId = existing?.memberId ?: m.memberId
                endpointMember[ep] = memberId
                put(rescore(r.copy(members = members, removed = r.removed - memberId)))
                broadcastState(gid)
                sendEntries(listOf(ep), replicas.value[gid]!!.entries.values)
            }
            "ENTRIES" -> {
                val valid = msg.optJSONArray("entries")?.objects().orEmpty().mapNotNull { parseEntry(it) }.filter { e ->
                    e.targetNo in 1..r.targetCount && r.members.any { it.memberId == e.cardMemberId } &&
                        r.members.any { it.memberId == e.scorerMemberId && it.hasPhone }
                }
                val (merged, changed) = LiveRules.mergeEntries(r.entries, valid)
                if (changed.isEmpty()) return@withLock
                put(r.copy(entries = merged))
                sendEntries(nearby.endpoints - ep, changed)
                scope.launch { runCatching { pushEntries(gid) } }
            }
            "LEAVE" -> {
                val id = endpointMember.remove(ep) ?: return@withLock
                put(rescore(r.copy(
                    members = r.members.filter { it.memberId != id },
                    entries = r.entries.filterKeys { it.second != id && it.third != id },
                    removed = r.removed + id,
                )))
                broadcastState(gid)
                nearby.disconnect(ep)
            }
            "_GONE" -> endpointMember.remove(ep)
        }
    }

    private suspend fun memberMessage(gid: String, ep: String, type: String, msg: JSONObject) = lock.withLock {
        val r = replicas.value[gid] ?: return@withLock
        when (type) {
            "_CONNECTED" -> {
                // Back in range: say who we are, and send everything we have (ours and what the server gave us).
                val me = r.members.firstOrNull { it.memberId == myMemberId(gid) } ?: return@withLock
                nearby.send(ep, JSONObject().put("t", "HELLO").put("v", PROTOCOL).put("code", r.code).put("groupId", gid)
                    .put("hostTerm", r.hostTerm).put("member", memberJson(me)))
                sendEntries(listOf(ep), r.entries.values)
            }
            "STATE" -> {
                val g = msg.getJSONObject("group")
                if (g.optString("groupId") != gid) return@withLock
                // From a host that's since been taken over from: tell it (in HELLO) and wait for the new one.
                if (g.optInt("hostTerm", 0) < r.hostTerm) {
                    val me = r.members.firstOrNull { it.memberId == myMemberId(gid) } ?: return@withLock
                    nearby.send(ep, JSONObject().put("t", "HELLO").put("v", PROTOCOL).put("code", r.code).put("groupId", gid)
                        .put("hostTerm", r.hostTerm).put("member", memberJson(me)))
                    return@withLock
                }
                lastFromHost[gid] = System.currentTimeMillis()
                val next = parseGroup(g, r)
                if (next != r) put(next)
            }
            "ENTRIES" -> {
                val list = msg.optJSONArray("entries")?.objects().orEmpty().mapNotNull { parseEntry(it) }
                val (merged, changed) = LiveRules.mergeEntries(r.entries, list)
                if (changed.isNotEmpty()) put(r.copy(entries = merged))
            }
            "NO" -> serverProblem.update { it + (gid to msg.optString("message")) }
        }
    }

    /** Host: the group to every phone in range, each told which member it is (call with [lock] held). */
    private fun broadcastState(groupId: String) {
        if (nearbyGroup != groupId) return
        val r = replicas.value[groupId] ?: return
        for (ep in nearby.endpoints) {
            val me = endpointMember[ep] ?: continue
            nearby.send(ep, JSONObject().put("t", "STATE").put("v", PROTOCOL).put("group", groupJson(r, me)))
        }
    }

    private fun sendEntries(endpoints: Collection<String>, entries: Collection<LiveRules.Entry>) {
        if (endpoints.isEmpty() || entries.isEmpty()) return
        entries.chunked(ENTRIES_PER_MESSAGE).forEach { nearby.send(endpoints, entriesMessage(it)) }
    }

    private fun entriesMessage(list: List<LiveRules.Entry>) =
        JSONObject().put("t", "ENTRIES").put("v", PROTOCOL).put("entries", JSONArray().apply { list.forEach { put(entryJson(it)) } })

    // ---- The group on this phone ---------------------------------------------------------------------------

    /** Host: if the scorers no longer fit (someone joined or left), the usual ones again (the server does the same). */
    private fun rescore(r: LiveReplica): LiveReplica {
        if (r.status != LiveRules.STARTED) return r
        val current = r.members.associate { it.memberId to it.scorers }
        if (current.values.any { it.isNotEmpty() } && LiveRules.validate(r.method, r.ruleMembers(), current).isEmpty()) return r
        val (scorers, problems) = LiveRules.defaultScorers(r.method, r.ruleMembers())
        if (problems.isNotEmpty()) return r
        return r.copy(members = r.members.map { it.copy(scorers = scorers[it.memberId].orEmpty()) })
    }

    /** Stores the group (memory and file). Call with [lock] held. */
    private fun put(r: LiveReplica, save: Boolean = true) {
        replicas.update { it + (r.groupId to r) }
        if (save) runCatching { File(dir, "${r.groupId}.json").writeText(fileJson(r).toString()) }
    }

    private fun load(groupId: String): LiveReplica? = runCatching {
        val json = JSONObject(File(dir, "$groupId.json").readText())
        parseGroup(json, null).copy(
            entries = json.optJSONArray("entries")?.objects().orEmpty().mapNotNull { parseEntry(it) }.associateBy { it.key },
            removed = json.optJSONArray("removed").strings().toSet(),
            manualChecks = json.optJSONArray("manualChecks")?.objects().orEmpty().map { it.getInt("targetNo") to it.getString("cardMemberId") }.toSet(),
        )
    }.getOrNull()

    private suspend fun forget(groupId: String) {
        prefs.edit().remove(groupId).remove("$groupId:code").remove("$groupId:method").remove("$groupId:host").remove("$groupId:server").apply()
        lock.withLock { replicas.update { it - groupId } }
        if (groupId !in closing()) File(dir, "$groupId.json").delete()
    }

    private fun stateOf(r: LiveReplica): LiveGroupState {
        val me = myMemberId(r.groupId) ?: ""
        return LiveGroupState(
            groupId = r.groupId, code = r.code, method = r.method, roundType = r.roundType, targetCount = r.targetCount,
            shootDate = r.shootDate, locationId = r.locationId, locationName = r.locationName, status = r.status,
            myMemberId = me,
            myKind = r.members.firstOrNull { it.memberId == me }?.kind ?: if (isHost(r.groupId)) LiveRules.HOST else LiveRules.JOINED,
            members = r.members.sortedBy { it.sortOrder },
            entries = r.entries.values.map { LiveEntry(it.targetNo, it.cardMemberId, it.scorerMemberId, it.target, it.target.totalPoints, it.enteredUtc) },
            cells = LiveRules.cells(r.entries.values, r.members.associate { it.memberId to it.scorers }),
            manualChecks = r.manualChecks,
            expected = r.expected,
            formerHosts = r.formerHosts,
        )
    }

    /**
     * Brings this phone's rounds in line with the group: a round for every member (copies of other phones' cards),
     * friends who joined on their own phone become copies, and every saved score for a card this phone doesn't score is
     * copied in (double / 3+ scorers: every card once both scorers match, or once checked by hand with no signal).
     * A closed group finishes everyone's round here.
     */
    private suspend fun applyToRounds(s: LiveGroupState) {
        if (!isLive(s.groupId)) return
        val local = rounds.groupRounds(s.groupId).associateBy { it.id }
        val added = prefs.getStringSet("${s.groupId}:added", emptySet()).orEmpty()
        val friends = accounts.cachedFriends().map { it.archerId }.toSet()
        for (m in s.members) {
            val isMe = m.memberId == s.myMemberId
            // Friends' rounds go to them from the phone that added them, or from a host who took over and is their friend.
            val role = when {
                isMe -> GroupRole.SCORER
                m.kind == "FRIEND" && m.memberId !in s.formerHosts &&
                    (m.memberId in added || (s.isHost && m.archerId != null && m.archerId in friends)) -> GroupRole.FRIEND
                m.kind == "GUEST" && (s.isHost || m.memberId in added) -> GroupRole.GUEST
                else -> GroupRole.MIRROR
            }
            val existing = local[m.memberId]
            if (existing == null) {
                rounds.addGroupRound(
                    s.groupId, m.memberId, role, if (isMe) null else m.name, m.sortOrder, s.shootDate, s.targetCount,
                    s.roundType, m.archerClass, m.ageGroup, m.bowStyle, s.locationId, s.locationName,
                )
            } else if (existing.groupRole != role || existing.groupOrder != m.sortOrder || (!isMe && existing.groupName != m.name)) {
                rounds.setGroupMember(m.memberId, role, if (isMe) null else m.name, m.sortOrder)
            }
        }
        for ((key, target) in savedTargets(s)) {
            val (targetNo, card) = key
            val current = rounds.get(card) ?: continue
            // Cards this phone scores are saved as they're entered; only fill in blanks (joined again, 2.3.01).
            if (!s.checked && s.canScore(card) && !current.target(targetNo).isBlank()) continue
            if (current.target(targetNo) != target) rounds.saveTarget(card, target)
        }
        if (s.closed) {
            val all = rounds.groupRounds(s.groupId)
            // Cancelled before anyone scored: nothing worth keeping.
            if (all.none { (rounds.get(it.id)?.targetsComplete ?: 0) > 0 }) rounds.deleteGroup(s.myMemberId)
            else all.filter { it.status != RoundStatus.COMPLETED }.forEach { rounds.setStatus(it.id, RoundStatus.COMPLETED) }
            forget(s.groupId)
        }
    }

    /** The saved score for each target and card: matched, or (no signal) checked by hand on this phone. */
    private fun savedTargets(s: LiveGroupState): Map<Pair<Int, String>, TargetScore> {
        val byCell = s.entries.groupBy { it.targetNo to it.cardMemberId }
        return byCell.mapNotNull { (key, list) ->
            val scorers = s.member(key.second)?.scorers.orEmpty()
            val state = s.cells[key]
            val pick = when {
                state == LiveRules.MATCHED -> list.filter { scorers.isEmpty() || it.scorerMemberId in scorers }.maxByOrNull { it.enteredUtc }
                state == LiveRules.WAITING && key in s.manualChecks -> list.firstOrNull { it.scorerMemberId == s.myMemberId }
                else -> null
            } ?: return@mapNotNull null
            key to pick.target
        }.toMap()
    }

    // ---- JSON (the server's shapes; phone to phone uses the same) ------------------------------------------

    private fun memberJson(m: LiveMember) = JSONObject()
        .put("memberId", m.memberId)
        .put("kind", m.kind)
        .put("archerId", m.archerId ?: JSONObject.NULL)
        .put("name", m.name)
        .put("archerClass", if (m.archerClass == ArcherClass.LADIES) "L" else "G")
        .put("ageGroup", m.ageGroup.name)
        .put("bowStyle", m.bowStyle.code)
        .put("sortOrder", m.sortOrder)
        .put("scorers", JSONArray().apply { m.scorers.forEach { put(it) } })

    private fun parseMember(m: JSONObject) = LiveMember(
        memberId = m.getString("memberId"),
        kind = m.optString("kind", LiveRules.JOINED),
        archerId = if (m.isNull("archerId")) null else m.optString("archerId"),
        name = if (m.isNull("name")) "?" else m.optString("name", "?"),
        archerClass = if (m.getString("archerClass") == "L") ArcherClass.LADIES else ArcherClass.GENTS,
        ageGroup = AgeGroup.valueOf(m.getString("ageGroup")),
        bowStyle = BowStyle.fromCode(m.getString("bowStyle")) ?: BowStyle.entries.first(),
        sortOrder = m.optInt("sortOrder"),
        scorers = m.optJSONArray("scorers").strings(),
    )

    private fun entryJson(e: LiveRules.Entry) = JSONObject()
        .put("targetNo", e.targetNo)
        .put("cardMemberId", e.cardMemberId)
        .put("scorerMemberId", e.scorerMemberId)
        .put("target", e.target.toJson())
        .put("points", e.target.totalPoints)
        .put("enteredUtc", e.enteredUtc.toString())

    private fun parseEntry(e: JSONObject): LiveRules.Entry? = runCatching {
        LiveRules.Entry(
            targetNo = e.getInt("targetNo"),
            cardMemberId = e.getString("cardMemberId"),
            scorerMemberId = e.getString("scorerMemberId"),
            target = e.getJSONObject("target").toTargetScore(),
            enteredUtc = Instant.parse(e.getString("enteredUtc")),
        )
    }.getOrNull()

    /** The group as GET /api/groups/{id} shows it (no entries): what the host's phone sends the others. */
    private fun groupJson(r: LiveReplica, me: String?) = JSONObject()
        .put("groupId", r.groupId)
        .put("code", r.code)
        .put("method", r.method)
        .put("roundType", r.roundType.name)
        .put("targetCount", r.targetCount)
        .put("shootDate", r.shootDate.toString())
        .put("locationId", r.locationId ?: JSONObject.NULL)
        .put("locationName", r.locationName ?: JSONObject.NULL)
        .put("status", r.status)
        // Phone to phone only (the server doesn't keep these): Start waits for this many (2.3.01); who's host, and since when.
        .put("expected", r.expected)
        .put("hostTerm", r.hostTerm)
        .put("formerHosts", JSONArray().apply { r.formerHosts.forEach { put(it) } })
        .apply {
            if (me != null) put("me", JSONObject().put("memberId", me).put("kind", r.members.firstOrNull { it.memberId == me }?.kind ?: LiveRules.JOINED))
        }
        .put("members", JSONArray().apply { r.members.forEach { put(memberJson(it)) } })

    private fun fileJson(r: LiveReplica) = groupJson(r, null)
        .put("entries", JSONArray().apply { r.entries.values.forEach { put(entryJson(it)) } })
        .put("removed", JSONArray().apply { r.removed.forEach { put(it) } })
        .put("manualChecks", JSONArray().apply { r.manualChecks.forEach { put(JSONObject().put("targetNo", it.first).put("cardMemberId", it.second)) } })

    /** A group from the server or the host's phone; entries and this phone's own notes come from [base]. */
    private fun parseGroup(json: JSONObject, base: LiveReplica?) = LiveReplica(
        groupId = json.getString("groupId"),
        code = json.getString("code"),
        method = json.getString("method"),
        roundType = RoundType.entries.firstOrNull { it.name == json.optString("roundType") } ?: RoundType.BIG_GAME,
        targetCount = json.getInt("targetCount"),
        shootDate = LocalDate.parse(json.getString("shootDate").take(10)),
        locationId = if (json.isNull("locationId")) null else json.optString("locationId").ifEmpty { null },
        locationName = if (json.isNull("locationName")) null else json.optString("locationName").ifEmpty { null },
        status = LiveRules.laterStatus(base?.status ?: LiveRules.OPEN, json.optString("status", LiveRules.OPEN)),
        members = json.optJSONArray("members")?.objects().orEmpty().map { parseMember(it) },
        entries = base?.entries.orEmpty(),
        removed = base?.removed.orEmpty(),
        manualChecks = base?.manualChecks.orEmpty(),
        expected = if (json.has("expected")) json.optInt("expected") else base?.expected ?: 0,
        hostTerm = maxOf(base?.hostTerm ?: 0, json.optInt("hostTerm", 0)),
        formerHosts = base?.formerHosts.orEmpty() + json.optJSONArray("formerHosts").strings(),
    )

    /** The host's group for PUT /api/groups/{id}: you, friends and guests (archers on their own phones join themselves). */
    private fun putBody(r: LiveReplica, status: String, everyoneThere: Boolean): JSONObject {
        val members = JSONArray()
        for (m in r.members.filter { it.kind != LiveRules.JOINED }) {
            members.put(
                JSONObject()
                    .put("memberId", m.memberId)
                    .put("kind", m.kind)
                    .put("archerId", if (m.kind == LiveRules.GUEST) JSONObject.NULL else m.archerId ?: JSONObject.NULL)
                    .put("guestName", if (m.kind == LiveRules.GUEST) m.name else JSONObject.NULL)
                    .put("archerClass", if (m.archerClass == ArcherClass.LADIES) "L" else "G")
                    .put("ageGroup", m.ageGroup.name)
                    .put("bowStyle", m.bowStyle.code)
                    .put("roundId", m.memberId),
            )
        }
        return JSONObject()
            .put("code", r.code)
            .put("method", r.method)
            .put("roundType", r.roundType.name)
            .put("targetCount", r.targetCount)
            .put("shootDate", r.shootDate.toString())
            .put("locationId", r.locationId ?: JSONObject.NULL)
            .put("status", status)
            .put("members", members)
            .apply {
                if (everyoneThere) {
                    put("order", JSONArray().apply { r.members.sortedBy { it.sortOrder }.forEach { put(it.memberId) } })
                    if (status == LiveRules.STARTED && r.members.all { it.scorers.isNotEmpty() }) {
                        put("scorers", JSONArray().apply {
                            r.members.forEach { m -> m.scorers.forEach { put(JSONObject().put("cardMemberId", m.memberId).put("scorerMemberId", it)) } }
                        })
                    }
                }
            }
    }

    private fun joinBody(code: String, groupId: String?, me: LiveMember) = JSONObject()
        .put("code", code)
        .put("groupId", groupId ?: JSONObject.NULL)
        .put("memberId", me.memberId)
        .put("archerClass", if (me.archerClass == ArcherClass.LADIES) "L" else "G")
        .put("ageGroup", me.ageGroup.name)
        .put("bowStyle", me.bowStyle.code)
        .put("roundId", me.memberId)

    private fun token(): String = accounts.account.value?.token
        ?: throw ApiException(401, "not_signed_in", "Sign in (Profile) to use live groups.")

    private suspend fun <T> call(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: ApiException) {
            if (e.status == 401) accounts.clear()
            throw e
        }
    }

    companion object {
        private const val TAG = "LiveGroups"
        private const val CLOSING = "closing"
        /** Phone-to-phone message version (2.3). */
        const val PROTOCOL = 1
        private const val POLL_MS = 3_000L
        private const val LINGER_MS = 120_000L
        private const val HOST_FRESH_MS = 20_000L
        private const val JOIN_TIMEOUT_MS = 45_000L
        private const val ENTRIES_PER_MESSAGE = 40

        /** Join codes: 6 characters, no 0/O, 1/I/L (easy to read out). Same as the server's. */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun newCode(): String = (1..6).map { CODE_ALPHABET.random() }.joinToString("")

        /** A code typed or scanned: the last 6 code characters in it (a QR holds "fieldarchery:join:CODE"). */
        fun cleanCode(text: String): String = text.uppercase().filter { it in CODE_ALPHABET }.takeLast(6)

        /** What the join QR code holds. */
        fun qrText(code: String) = "fieldarchery:join:$code"
    }
}

private typealias EntryKey = Triple<Int, String, String>

/** A live group as this phone keeps it (2.3): the group, every scorer's entries, and this phone's own notes. */
internal data class LiveReplica(
    val groupId: String,
    val code: String,
    val method: String,
    val roundType: RoundType,
    val targetCount: Int,
    val shootDate: LocalDate,
    val locationId: String?,
    val locationName: String?,
    val status: String,
    val members: List<LiveMember>,
    val entries: Map<EntryKey, LiveRules.Entry> = emptyMap(),
    /** Host: archers who left phone to phone (taken off the server too). */
    val removed: Set<String> = emptySet(),
    /** (target, card) checked by hand on this phone with no signal. */
    val manualChecks: Set<Pair<Int, String>> = emptySet(),
    /** How many archers the host said would be in the group (0: not said). */
    val expected: Int = 0,
    /** Goes up each time someone takes over as host: the newest host wins phone to phone. */
    val hostTerm: Int = 0,
    /** Hosts who were taken over from (their cards aren't sent to them as friends' rounds). */
    val formerHosts: Set<String> = emptySet(),
) {
    fun ruleMembers() = members.map { LiveRules.Member(it.memberId, it.kind, it.sortOrder) }
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

/** Nothing entered on this target yet. */
private fun TargetScore.isBlank() =
    overridePoints == null && arrows.isEmpty() && animals.all { it == AnimalResult.Pending }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
