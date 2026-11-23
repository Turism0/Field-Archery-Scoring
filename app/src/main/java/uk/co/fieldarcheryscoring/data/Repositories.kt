package uk.co.fieldarcheryscoring.data

import uk.co.fieldarcheryscoring.domain.ArcherNames
import uk.co.fieldarcheryscoring.domain.Weather
import uk.co.fieldarcheryscoring.domain.RoundType
import uk.co.fieldarcheryscoring.domain.TargetType
import uk.co.fieldarcheryscoring.domain.WoodsmanScoring
import uk.co.fieldarcheryscoring.domain.newTarget
import uk.co.fieldarcheryscoring.data.local.LocationState
import uk.co.fieldarcheryscoring.domain.LocationStatus
import uk.co.fieldarcheryscoring.domain.RoundLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import uk.co.fieldarcheryscoring.data.local.AnimalEntity
import uk.co.fieldarcheryscoring.data.local.AnimalState
import uk.co.fieldarcheryscoring.data.local.GroupRole
import uk.co.fieldarcheryscoring.data.local.ProfileDao
import uk.co.fieldarcheryscoring.data.local.ProfileEntity
import uk.co.fieldarcheryscoring.data.local.RoundDao
import uk.co.fieldarcheryscoring.data.local.RoundData
import uk.co.fieldarcheryscoring.data.local.RoundEntity
import uk.co.fieldarcheryscoring.data.local.SyncState
import uk.co.fieldarcheryscoring.data.local.TargetEntity
import uk.co.fieldarcheryscoring.domain.AgeGroup
import uk.co.fieldarcheryscoring.domain.AnimalResult
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.domain.Round
import uk.co.fieldarcheryscoring.domain.RoundStatus
import uk.co.fieldarcheryscoring.domain.TargetScore
import uk.co.fieldarcheryscoring.domain.targetsDescription
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Profile stored on the phone only. */
data class Profile(
    val realName: String? = null,
    val username: String? = null,
    val dateOfBirth: LocalDate? = null,
    val archerClass: ArcherClass? = null,
    val ageGroupManual: AgeGroup? = null,
    val mainBowStyle: BowStyle? = null,
)

class ProfileRepository(private val dao: ProfileDao) {
    val profile: Flow<Profile> = dao.observe().map { it?.toProfile() ?: Profile() }

    suspend fun get(): Profile = dao.get()?.toProfile() ?: Profile()

    /** Saves the profile screen. The username isn't edited there (it comes from the account), so keep it. */
    suspend fun save(p: Profile) = dao.upsert(
        ProfileEntity(
            realName = p.realName?.trim()?.ifBlank { null },
            username = dao.get()?.username,
            dateOfBirth = p.dateOfBirth,
            archerClass = p.archerClass,
            ageGroupManual = p.ageGroupManual,
            mainBowStyle = p.mainBowStyle,
        ),
    )

    /** Records the account username after signing in. */
    suspend fun setUsername(username: String) {
        val current = dao.get() ?: ProfileEntity()
        dao.upsert(current.copy(username = username))
    }

    private fun ProfileEntity.toProfile() =
        Profile(realName, username, dateOfBirth, archerClass, ageGroupManual, mainBowStyle)
}

/** Light round info for lists. */
data class RoundSummary(
    val id: String,
    val shootDate: LocalDate,
    val targetCount: Int,
    val classLine: String,
    val totalPoints: Int,
    val status: RoundStatus,
    val syncState: SyncState,
    val locationName: String? = null,
    /** How many archers were in the group (0 = not a group round). */
    val groupSize: Int = 0,
    /** "36 targets · 37 animals (1 predator/prey)". */
    val targetsLine: String = "$targetCount targets",
    // For the history filters (2.2).
    val locationId: String? = null,
    val roundType: RoundType = RoundType.BIG_GAME,
    val bowStyle: BowStyle? = null,
    /** The archer's notes (3.2.21), shown as a one-line preview. */
    val notes: String? = null,
    /** The bow and arrows it was shot with (3.2.24), for the history filters. */
    val bowId: String? = null,
    val arrowId: String? = null,
)

/** Someone being added to a group round on this phone: a friend (account) or a guest (name only). */
data class NewGroupMember(
    val role: GroupRole,
    val name: String,
    /** The friend's server archer id; null for a guest. */
    val archerId: String?,
    val archerClass: ArcherClass,
    val ageGroup: AgeGroup,
    val bowStyle: BowStyle,
)

/** One archer's round in a group scored on this phone, in scoring order (the scorer first). */
data class GroupMember(
    val roundId: String,
    val role: GroupRole,
    /** "You" for the scorer, else the name you gave them (3.2.23), the friend's username or the guest's name. */
    val name: String,
    val round: Round,
    /** For a friend's round: PENDING = not sent yet (or changed since), SYNCED = sent, FAILED = refused. */
    val syncState: SyncState,
    /** 3.2.23: their account (friends, and archers on their own phone in a live group); null for you and guests. */
    val archerId: String? = null,
    /** 3.2.23: their username, shown small under a name you gave them (null when [name] is the username). */
    val usernameUnder: String? = null,
)

class RoundRepository(
    private val dao: RoundDao,
    private val clock: Clock = Clock.systemUTC(),
    /** Called after any change that may need uploading (asks the background sync to run). */
    private val onChanged: () -> Unit = {},
    /** 3.2.23: names you've given other archers (account id → name), shown in groups instead of usernames. */
    private val names: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap()),
) {
    /** 3.2.17: weather for rounds (looked up by the server and downloaded), by round id. */
    val weather: Flow<Map<String, Weather>> = dao.observeWeather().map { list -> list.associate { it.roundId to it.toWeather() } }

    fun weatherFor(roundId: String): Flow<Weather?> = dao.observeWeatherFor(roundId).map { it?.toWeather() }

    val rounds: Flow<List<RoundSummary>> = dao.observeRounds().map { list ->
        list.map { row ->
            val it = row.round
            RoundSummary(
                id = it.id,
                shootDate = it.shootDate,
                targetCount = it.targetCount,
                classLine = (if (it.type() == RoundType.WOODSMAN) "Woodsman · " else "") +
                    "${it.archerClass.displayName} ${it.ageGroup.displayName} ${it.bowStyle.code}",
                totalPoints = it.totalPoints,
                status = it.status,
                syncState = it.syncState,
                locationName = it.locationName,
                locationId = it.locationId,
                roundType = it.type(),
                bowStyle = it.bowStyle,
                notes = it.notes,
                bowId = it.bowId,
                arrowId = it.arrowId,
                groupSize = row.groupSize,
                targetsLine = if (it.type() == RoundType.WOODSMAN) targetsDescription(it.targetCount, it.targetCount, 0, 0)
                else targetsDescription(it.targetCount, row.animalCount, row.predatorPreyCount, row.fanCount),
            )
        }
    }

    /**
     * Every target save also updates the round row (total, updated time), so watching the
     * round table is enough; the full round is then read in one transaction.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun round(id: String): Flow<Round?> = dao.observeRound(id).mapLatest {
        dao.loadRound(id)?.takeUnless { it.round.deleted || it.round.hiddenOwner != null }?.let { d -> d.round.toDomain(d.targets, d.animals) }
    }

    /** Every finished round of your own, in full (for history filters, personal bests, trends and seasons). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val finishedRounds: Flow<List<Round>> = dao.observeOwnFinished().mapLatest {
        dao.loadOwnFinished().map { it.toDomain() }
    }

    /** The group a round is in, or null. */
    suspend fun groupIdOf(roundId: String): String? = dao.getRound(roundId)?.groupId

    /** The round as it is now (not a flow), or null if it's gone. */
    suspend fun get(id: String): Round? =
        dao.loadRound(id)?.takeUnless { it.round.deleted || it.round.hiddenOwner != null }?.let { d -> d.round.toDomain(d.targets, d.animals) }

    /** Creates a round with every target set up as a Standard target. Returns the new id. */
    suspend fun createRound(
        shootDate: LocalDate,
        targetCount: Int,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
        type: RoundType = RoundType.BIG_GAME,
        /** The target the archer started on (2.7), or null. */
        startTarget: Int? = null,
        /** The archer's notes (3.2.21), or null. */
        notes: String? = null,
        /** The bow and arrows (3.2.24), or null. */
        bowId: String? = null,
        arrowId: String? = null,
    ): String {
        require(targetCount in 1..MAX_TARGETS) { "Targets must be 1 to $MAX_TARGETS" }
        val now = Instant.now(clock)
        val id = UUID.randomUUID().toString()
        val targets = (1..targetCount).map { newTarget(type, it) }
        dao.createRound(
            round = RoundEntity(
                id = id,
                shootDate = shootDate,
                targetCount = targetCount,
                archerClass = archerClass,
                ageGroup = ageGroup,
                bowStyle = bowStyle,
                status = RoundStatus.IN_PROGRESS,
                createdAt = now,
                updatedAt = now,
                roundType = type.takeIf { it != RoundType.BIG_GAME }?.name,
                startTarget = startTarget?.takeIf { it in 1..MAX_START_TARGET },
                notes = cleanNotes(notes),
                bowId = bowId,
                arrowId = arrowId?.takeIf { bowId != null },
            ),
            targets = targets.map { it.toEntity(id, now) },
            animals = targets.flatMap { it.toAnimalEntities(id) },
        )
        return id
    }

    /**
     * Starts a group round scored on this phone: the scorer's own round plus one per friend and guest, all with the
     * same date, number of targets and type, each with their own class, age group and bow style. Returns the
     * scorer's round id.
     */
    suspend fun createGroupRound(
        shootDate: LocalDate,
        targetCount: Int,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
        type: RoundType,
        others: List<NewGroupMember>,
        /** Live groups make the group id first (it's also the server's); others may be empty (people join later). */
        groupId: String = UUID.randomUUID().toString(),
        live: Boolean = false,
        /** The target the group started on (2.7), the same on everyone's card, or null. */
        startTarget: Int? = null,
        /** The scorer's notes (3.2.21): on their own card only. */
        notes: String? = null,
        /** The scorer's bow and arrows (3.2.24): on their own card only. */
        bowId: String? = null,
        arrowId: String? = null,
    ): String {
        require(targetCount in 1..MAX_TARGETS) { "Targets must be 1 to $MAX_TARGETS" }
        require(live || others.isNotEmpty()) { "A group needs someone else" }
        val now = Instant.now(clock)
        fun make(order: Int, role: GroupRole, name: String?, archerId: String?, cls: ArcherClass, age: AgeGroup, bow: BowStyle): RoundData {
            val id = UUID.randomUUID().toString()
            val targets = (1..targetCount).map { newTarget(type, it) }
            return RoundData(
                round = RoundEntity(
                    id = id,
                    shootDate = shootDate,
                    targetCount = targetCount,
                    archerClass = cls,
                    ageGroup = age,
                    bowStyle = bow,
                    status = RoundStatus.IN_PROGRESS,
                    createdAt = now,
                    updatedAt = now,
                    roundType = type.takeIf { it != RoundType.BIG_GAME }?.name,
                    groupId = groupId,
                    groupRole = role,
                    groupName = name,
                    groupArcherId = archerId,
                    groupOrder = order,
                    startTarget = startTarget?.takeIf { it in 1..MAX_START_TARGET },
                ),
                targets = targets.map { it.toEntity(id, now) },
                animals = targets.flatMap { it.toAnimalEntities(id) },
            )
        }
        val mine = make(0, GroupRole.SCORER, null, null, archerClass, ageGroup, bowStyle)
            .let { it.copy(round = it.round.copy(notes = cleanNotes(notes), bowId = bowId, arrowId = arrowId?.takeIf { bowId != null })) }
        val rest = others.mapIndexed { i, m -> make(i + 1, m.role, m.name.trim(), m.archerId, m.archerClass, m.ageGroup, m.bowStyle) }
        dao.createRounds(listOf(mine) + rest)
        return mine.round.id
    }

    /**
     * Live groups: a round on this phone for someone in the group – this archer's own (SCORER, after joining) or a copy of
     * someone else's card (MIRROR). [id] is their member id in the group. Does nothing if it's already here.
     */
    suspend fun addGroupRound(
        groupId: String,
        id: String,
        role: GroupRole,
        name: String?,
        order: Int,
        shootDate: LocalDate,
        targetCount: Int,
        type: RoundType,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
        locationId: String?,
        locationName: String?,
        startTarget: Int? = null,
        /** 3.2.23: the member's account (not for guests), so names you give them can be shown. */
        archerId: String? = null,
    ) {
        if (dao.getRound(id) != null) return
        val now = Instant.now(clock)
        val targets = (1..targetCount).map { newTarget(type, it) }
        val kit = if (role == GroupRole.SCORER) groupEquipment.remove(groupId) else null
        dao.createRound(
            round = RoundEntity(
                id = id,
                shootDate = shootDate,
                targetCount = targetCount,
                archerClass = archerClass,
                ageGroup = ageGroup,
                bowStyle = bowStyle,
                status = RoundStatus.IN_PROGRESS,
                createdAt = now,
                updatedAt = now,
                roundType = type.takeIf { it != RoundType.BIG_GAME }?.name,
                groupId = groupId,
                groupRole = role,
                groupName = name,
                groupArcherId = archerId,
                groupOrder = order,
                locationId = locationId,
                locationName = locationName,
                locationState = if (locationId != null) uk.co.fieldarcheryscoring.data.local.LocationState.SET else null,
                startTarget = startTarget?.takeIf { it in 1..MAX_START_TARGET },
                bowId = kit?.first,
                arrowId = kit?.second,
            ),
            targets = targets.map { it.toEntity(id, now) },
            animals = targets.flatMap { it.toAnimalEntities(id) },
        )
    }

    /** 3.2.24: the bow and arrows picked when joining a live group, for this archer's round once it's made. */
    private val groupEquipment = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

    /** Joining a live group (3.2.24): your bow and arrows go on your round in it, now or when it's made. */
    suspend fun useEquipmentInGroup(groupId: String, bowId: String?, arrowId: String?) {
        if (bowId == null) return
        val mine = dao.getGroup(groupId).firstOrNull { it.groupRole == GroupRole.SCORER }
        if (mine != null) setEquipment(mine.id, bowId, arrowId) else groupEquipment[groupId] = bowId to arrowId
    }

    /** Live groups: a member's role, name or order changed on the server. */
    suspend fun setGroupMember(id: String, role: GroupRole, name: String?, order: Int) =
        dao.setGroupMember(id, role.name, name, order)

    /** 3.2.23: a live group member's account, for names you give them (rows from before 3.2.23 have none). */
    suspend fun setGroupArcher(id: String, archerId: String?) = dao.setGroupArcher(id, archerId)

    /** The rows of a group on this phone (live groups use them to match members). */
    suspend fun groupRounds(groupId: String): List<RoundEntity> = dao.getGroup(groupId)

    /** Everyone in the round's group, in scoring order; empty when it isn't a group round. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun group(roundId: String): Flow<List<GroupMember>> = dao.observeRound(roundId)
        .map { it?.groupId }
        .distinctUntilChanged()
        .flatMapLatest { groupId ->
            if (groupId == null) flowOf(emptyList())
            else kotlinx.coroutines.flow.combine(dao.observeGroup(groupId), names) { rows, given -> rows to given }.mapLatest { (rows, given) ->
                rows.mapNotNull { row ->
                    val d = dao.loadRound(row.id) ?: return@mapNotNull null
                    val you = row.groupRole == GroupRole.SCORER || row.groupRole == null
                    val archerId = row.groupArcherId.takeUnless { you }
                    GroupMember(
                        roundId = row.id,
                        role = row.groupRole ?: GroupRole.SCORER,
                        name = if (you) "You" else ArcherNames.display(row.groupName.orEmpty(), archerId, given),
                        round = d.round.toDomain(d.targets, d.animals),
                        syncState = row.syncState,
                        archerId = archerId,
                        usernameUnder = if (you) null else ArcherNames.usernameUnder(row.groupName, archerId, given),
                    )
                }
            }
        }

    /** The rounds a change to [roundId] applies to: its whole group, or just itself. */
    private suspend fun groupRoundsOf(roundId: String): List<RoundEntity> {
        val r = dao.getRound(roundId) ?: return emptyList()
        return r.groupId?.let { dao.getGroup(it) }?.takeIf { it.isNotEmpty() } ?: listOf(r)
    }

    /**
     * In a group everyone shoots the same target, so a new target type (e.g. predator/prey) is copied to the others
     * on that target, unless they've already been scored on it.
     */
    suspend fun setTypeForGroup(roundId: String, targetNo: Int, type: TargetType, fanAnimals: Int) {
        val now = Instant.now(clock)
        for (r in groupRoundsOf(roundId)) {
            if (r.id == roundId) continue
            val d = dao.loadRound(r.id) ?: continue
            val t = d.round.toDomain(d.targets, d.animals).target(targetNo)
            val untouched = t.overridePoints == null && t.arrows.isEmpty() && t.animals.all { it == AnimalResult.Pending }
            if (!untouched || (t.type == type && t.animals.size == (if (type == TargetType.FAN) fanAnimals else t.animals.size))) continue
            val updated = runCatching { t.withType(type, fanAnimals) }.getOrNull() ?: continue
            dao.saveTarget(updated.toEntity(r.id, now), updated.toAnimalEntities(r.id))
        }
        onChanged()
    }

    /** Finishes or reopens everyone's round in the group (or just this round). */
    suspend fun setGroupStatus(roundId: String, status: RoundStatus) {
        groupRoundsOf(roundId).forEach { setStatus(it.id, status) }
    }

    /** Deletes everyone's round in the group (or just this round). Friends' requests already sent are withdrawn. */
    suspend fun deleteGroup(roundId: String) {
        groupRoundsOf(roundId).forEach { delete(it.id) }
    }

    /** Guests' scores are temporary: removes any from groups started more than 30 days ago. */
    suspend fun deleteOldGuests() {
        dao.deleteGuestsBefore(Instant.now(clock).minus(java.time.Duration.ofDays(GUEST_DAYS)).toEpochMilli())
    }

    // ---- Editing a finished round (3.2.07, Pete): Save keeps the changes, Cancel puts the scores back ----

    /** Copies of a round (and everyone in its group) before editing. */
    suspend fun snapshot(roundId: String): List<Round> = groupRoundsOf(roundId).mapNotNull { get(it.id) }

    /** The targets in [before] that differ now (and are still in the round): what Cancel would put back. */
    private suspend fun changes(before: List<Round>): List<Pair<String, TargetScore>> = before.flatMap { b ->
        val now = get(b.id) ?: return@flatMap emptyList()
        b.targets.filter { it.targetNo <= now.targetCount && now.target(it.targetNo) != it }.map { b.id to it }
    }

    suspend fun changedSince(before: List<Round>): Boolean = changes(before).isNotEmpty()

    /**
     * Cancel: every target back as it was. Saved as a new edit, so a copy already backed up mid-edit is put back on
     * the server too. Round details (changed on their own screen, with their own Save) aren't touched.
     */
    suspend fun restore(before: List<Round>) {
        val now = Instant.now(clock)
        val back = changes(before)
        back.forEach { (id, t) -> dao.saveTarget(t.toEntity(id, now), t.toAnimalEntities(id)) }
        if (back.isNotEmpty()) onChanged()
    }

    suspend fun saveTarget(roundId: String, target: TargetScore) {
        dao.saveTarget(target.toEntity(roundId, Instant.now(clock)), target.toAnimalEntities(roundId))
        onChanged()
    }

    /**
     * Edits a round's details (what was chosen when it was created). Fewer targets removes the ones above the
     * new count; more targets adds unscored ones and reopens a finished round so they can be scored.
     */
    suspend fun updateDetails(
        roundId: String,
        shootDate: LocalDate,
        targetCount: Int,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
        /** The target the round started on (2.7; null = none); in a group it's the same for everyone. Default: unchanged. */
        startTarget: Int? = KEEP_START,
    ) {
        require(targetCount in 1..MAX_TARGETS) { "Targets must be 1 to $MAX_TARGETS" }
        val r = dao.getRound(roundId) ?: return
        val start = (if (startTarget == KEEP_START) r.startTarget else startTarget)?.takeIf { it in 1..MAX_START_TARGET }
        // In a group, the date, number of targets and start target are the same for everyone; class, age group and bow
        // are per archer.
        for (other in groupRoundsOf(roundId)) {
            if (other.id != roundId && (other.shootDate != shootDate || other.targetCount != targetCount || other.startTarget != start)) {
                updateOne(other, shootDate, targetCount, other.archerClass, other.ageGroup, other.bowStyle, start)
            }
        }
        updateOne(r, shootDate, targetCount, archerClass, ageGroup, bowStyle, start)
        onChanged()
    }

    private suspend fun updateOne(
        r: RoundEntity,
        shootDate: LocalDate,
        targetCount: Int,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
        startTarget: Int?,
    ) {
        val roundId = r.id
        val now = Instant.now(clock)
        val added = (r.targetCount + 1..targetCount).map { newTarget(r.type(), it) }
        dao.updateDetails(
            round = r.copy(
                shootDate = shootDate,
                targetCount = targetCount,
                archerClass = archerClass,
                ageGroup = ageGroup,
                bowStyle = bowStyle,
                startTarget = startTarget,
                status = if (added.isNotEmpty()) RoundStatus.IN_PROGRESS else r.status,
                updatedAt = now,
                syncState = SyncState.PENDING,
            ),
            addedTargets = added.map { it.toEntity(roundId, now) },
            addedAnimals = added.flatMap { it.toAnimalEntities(roundId) },
        )
    }

    suspend fun setStatus(roundId: String, status: RoundStatus) = touch(roundId) { it.copy(status = status) }

    /** The archer's notes on their own round (3.2.21); blank clears them. Only this round, not the rest of a group. */
    suspend fun setNotes(roundId: String, notes: String?) {
        val clean = cleanNotes(notes)
        if (dao.getRound(roundId)?.notes != clean) touch(roundId) { it.copy(notes = clean) }
    }

    /** The bow and arrows a round was shot with (3.2.24); your own round only, not the rest of a group. */
    suspend fun setEquipment(roundId: String, bowId: String?, arrowId: String?) {
        val r = dao.getRound(roundId) ?: return
        val arrows = arrowId?.takeIf { bowId != null }
        if (r.bowId != bowId || r.arrowId != arrows) touch(roundId) { it.copy(bowId = bowId, arrowId = arrows) }
    }

    /** Live groups (2.7): the host's start target for every card in the group on this phone (only where it differs). */
    suspend fun setGroupStartTarget(groupId: String, startTarget: Int?) {
        for (r in dao.getGroup(groupId)) {
            val start = startTarget?.takeIf { it in 1..MAX_START_TARGET }
            if (r.startTarget != start) touch(r.id) { it.copy(startTarget = start) }
        }
    }

    suspend fun delete(roundId: String) = touch(roundId) { it.copy(deleted = true) }

    private suspend fun touch(roundId: String, change: (RoundEntity) -> RoundEntity) {
        val r = dao.getRound(roundId) ?: return
        dao.upsertRound(change(r).copy(updatedAt = Instant.now(clock), syncState = SyncState.PENDING))
        onChanged()
    }

    companion object {
        /** updateDetails: leave the start target as it is. */
        const val KEEP_START = -1
        const val MAX_TARGETS = 100
        /** The highest starting target (3.1.03, Pete): any peg on the course, not just up to the number of targets shot. */
        const val MAX_START_TARGET = 999
        /** Group size limit, including the scorer. */
        const val MAX_GROUP = 8
        /** Days guests' scores are kept. */
        const val GUEST_DAYS = 30L
        /** Round notes (3.2.21): up to this many characters (the server's limit too). */
        const val MAX_NOTES = 500

        /** Notes as stored: trimmed, at most [MAX_NOTES] characters, null when blank. */
        fun cleanNotes(notes: String?): String? = notes?.trim()?.take(MAX_NOTES)?.takeIf { it.isNotEmpty() }
    }
}

// ---- Mapping ----

/** The round's type; rounds from before database v3 (null) are Big Game. */
fun RoundEntity.type(): RoundType = RoundType.entries.firstOrNull { it.name == roundType } ?: RoundType.BIG_GAME

/** A round as phone database rows → the domain model (also used for friends' rounds from the server). */
fun RoundData.toDomain(): Round = round.toDomain(targets, animals)

private fun RoundEntity.toDomain(targets: List<TargetEntity>, animals: List<AnimalEntity>): Round {
    val byTarget = animals.groupBy { it.targetNo }
    return Round(
        id = id,
        shootDate = shootDate,
        targetCount = targetCount,
        archerClass = archerClass,
        ageGroup = ageGroup,
        bowStyle = bowStyle,
        status = status,
        type = type(),
        createdAtMillis = createdAt.toEpochMilli(),
        startTarget = startTarget,
        notes = notes,
        bowId = bowId,
        arrowId = arrowId,
        location = RoundLocation(
            status = when (locationState) {
                null -> LocationStatus.NONE
                LocationState.PENDING_LOOKUP -> LocationStatus.LOOKING
                LocationState.NEEDS_NAME -> LocationStatus.NEEDS_NAME
                LocationState.SET -> LocationStatus.SET
                LocationState.PENDING_NAME -> LocationStatus.QUEUED
                LocationState.CHECK_NAME -> LocationStatus.CHECK
            },
            name = locationName,
            pending = PendingPlaceJson.decode(pendingLocation),
        ),
        targets = targets.map { t ->
            TargetScore(
                targetNo = t.targetNo,
                type = t.type,
                animals = byTarget[t.targetNo].orEmpty().sortedBy { it.animalIndex }.map { it.toResult() },
                overridePoints = t.overridePoints,
                arrows = WoodsmanScoring.decode(t.arrows),
            )
        },
    )
}

private fun AnimalEntity.toResult(): AnimalResult = when (state) {
    AnimalState.PENDING -> AnimalResult.Pending
    AnimalState.MISSED -> AnimalResult.Missed
    AnimalState.SCORED -> AnimalResult.Scored(arrowNo!!, hit!!)
}

private fun TargetScore.toEntity(roundId: String, now: Instant) = TargetEntity(
    roundId = roundId,
    targetNo = targetNo,
    type = type,
    overridePoints = overridePoints,
    points = totalPoints,
    updatedAt = now,
    arrows = if (type == TargetType.WOODSMAN) WoodsmanScoring.encode(arrows) else null,
)

private fun TargetScore.toAnimalEntities(roundId: String) = animals.mapIndexed { i, a ->
    AnimalEntity(
        roundId = roundId,
        targetNo = targetNo,
        animalIndex = i,
        state = when (a) {
            AnimalResult.Pending -> AnimalState.PENDING
            AnimalResult.Missed -> AnimalState.MISSED
            is AnimalResult.Scored -> AnimalState.SCORED
        },
        arrowNo = (a as? AnimalResult.Scored)?.arrowNo,
        hit = (a as? AnimalResult.Scored)?.hit,
        points = a.points,
    )
}


/** 3.2.17: a downloaded weather row → the domain model. */
fun uk.co.fieldarcheryscoring.data.local.RoundWeatherEntity.toWeather(): Weather = Weather(tempC, windKph, gustKph, rainMm, code)
