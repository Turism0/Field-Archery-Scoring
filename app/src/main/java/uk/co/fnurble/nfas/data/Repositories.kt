package uk.co.fnurble.nfas.data

import uk.co.fnurble.nfas.domain.RoundType
import uk.co.fnurble.nfas.domain.TargetType
import uk.co.fnurble.nfas.domain.WoodsmanScoring
import uk.co.fnurble.nfas.domain.newTarget
import uk.co.fnurble.nfas.data.local.LocationState
import uk.co.fnurble.nfas.domain.LocationStatus
import uk.co.fnurble.nfas.domain.RoundLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import uk.co.fnurble.nfas.data.local.AnimalEntity
import uk.co.fnurble.nfas.data.local.AnimalState
import uk.co.fnurble.nfas.data.local.GroupRole
import uk.co.fnurble.nfas.data.local.ProfileDao
import uk.co.fnurble.nfas.data.local.ProfileEntity
import uk.co.fnurble.nfas.data.local.RoundDao
import uk.co.fnurble.nfas.data.local.RoundData
import uk.co.fnurble.nfas.data.local.RoundEntity
import uk.co.fnurble.nfas.data.local.SyncState
import uk.co.fnurble.nfas.data.local.TargetEntity
import uk.co.fnurble.nfas.domain.AgeGroup
import uk.co.fnurble.nfas.domain.AnimalResult
import uk.co.fnurble.nfas.domain.ArcherClass
import uk.co.fnurble.nfas.domain.BowStyle
import uk.co.fnurble.nfas.domain.Round
import uk.co.fnurble.nfas.domain.RoundStatus
import uk.co.fnurble.nfas.domain.TargetScore
import uk.co.fnurble.nfas.domain.targetsDescription
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
    /** "You" for the scorer, else the friend's username or the guest's name. */
    val name: String,
    val round: Round,
    /** For a friend's round: PENDING = not sent yet (or changed since), SYNCED = sent, FAILED = refused. */
    val syncState: SyncState,
)

class RoundRepository(
    private val dao: RoundDao,
    private val clock: Clock = Clock.systemUTC(),
    /** Called after any change that may need uploading (asks the background sync to run). */
    private val onChanged: () -> Unit = {},
) {
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
        dao.loadRound(id)?.takeUnless { it.round.deleted }?.let { d -> d.round.toDomain(d.targets, d.animals) }
    }

    /** Creates a round with every target set up as a Standard target. Returns the new id. */
    suspend fun createRound(
        shootDate: LocalDate,
        targetCount: Int,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
        type: RoundType = RoundType.BIG_GAME,
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
    ): String {
        require(targetCount in 1..MAX_TARGETS) { "Targets must be 1 to $MAX_TARGETS" }
        require(others.isNotEmpty()) { "A group needs someone else" }
        val now = Instant.now(clock)
        val groupId = UUID.randomUUID().toString()
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
                ),
                targets = targets.map { it.toEntity(id, now) },
                animals = targets.flatMap { it.toAnimalEntities(id) },
            )
        }
        val mine = make(0, GroupRole.SCORER, null, null, archerClass, ageGroup, bowStyle)
        val rest = others.mapIndexed { i, m -> make(i + 1, m.role, m.name.trim(), m.archerId, m.archerClass, m.ageGroup, m.bowStyle) }
        dao.createRounds(listOf(mine) + rest)
        return mine.round.id
    }

    /** Everyone in the round's group, in scoring order; empty when it isn't a group round. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun group(roundId: String): Flow<List<GroupMember>> = dao.observeRound(roundId)
        .map { it?.groupId }
        .distinctUntilChanged()
        .flatMapLatest { groupId ->
            if (groupId == null) flowOf(emptyList())
            else dao.observeGroup(groupId).mapLatest { rows ->
                rows.mapNotNull { row ->
                    val d = dao.loadRound(row.id) ?: return@mapNotNull null
                    GroupMember(
                        roundId = row.id,
                        role = row.groupRole ?: GroupRole.SCORER,
                        name = if (row.groupRole == GroupRole.SCORER || row.groupRole == null) "You" else row.groupName.orEmpty(),
                        round = d.round.toDomain(d.targets, d.animals),
                        syncState = row.syncState,
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
    ) {
        require(targetCount in 1..MAX_TARGETS) { "Targets must be 1 to $MAX_TARGETS" }
        val r = dao.getRound(roundId) ?: return
        // In a group, the date and number of targets are the same for everyone; class, age group and bow are per archer.
        for (other in groupRoundsOf(roundId)) {
            if (other.id != roundId && (other.shootDate != shootDate || other.targetCount != targetCount)) {
                updateOne(other, shootDate, targetCount, other.archerClass, other.ageGroup, other.bowStyle)
            }
        }
        updateOne(r, shootDate, targetCount, archerClass, ageGroup, bowStyle)
        onChanged()
    }

    private suspend fun updateOne(
        r: RoundEntity,
        shootDate: LocalDate,
        targetCount: Int,
        archerClass: ArcherClass,
        ageGroup: AgeGroup,
        bowStyle: BowStyle,
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
                status = if (added.isNotEmpty()) RoundStatus.IN_PROGRESS else r.status,
                updatedAt = now,
                syncState = SyncState.PENDING,
            ),
            addedTargets = added.map { it.toEntity(roundId, now) },
            addedAnimals = added.flatMap { it.toAnimalEntities(roundId) },
        )
    }

    suspend fun setStatus(roundId: String, status: RoundStatus) = touch(roundId) { it.copy(status = status) }

    suspend fun delete(roundId: String) = touch(roundId) { it.copy(deleted = true) }

    private suspend fun touch(roundId: String, change: (RoundEntity) -> RoundEntity) {
        val r = dao.getRound(roundId) ?: return
        dao.upsertRound(change(r).copy(updatedAt = Instant.now(clock), syncState = SyncState.PENDING))
        onChanged()
    }

    companion object {
        const val MAX_TARGETS = 100
        /** Group size limit, including the scorer. */
        const val MAX_GROUP = 8
        /** Days guests' scores are kept. */
        const val GUEST_DAYS = 30L
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
        location = RoundLocation(
            status = when (locationState) {
                null -> LocationStatus.NONE
                LocationState.PENDING_LOOKUP -> LocationStatus.LOOKING
                LocationState.NEEDS_NAME -> LocationStatus.NEEDS_NAME
                LocationState.SET -> LocationStatus.SET
            },
            name = locationName,
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
