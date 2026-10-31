package uk.co.fnurble.nfas.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import uk.co.fnurble.nfas.data.local.AnimalEntity
import uk.co.fnurble.nfas.data.local.AnimalState
import uk.co.fnurble.nfas.data.local.ProfileDao
import uk.co.fnurble.nfas.data.local.ProfileEntity
import uk.co.fnurble.nfas.data.local.RoundDao
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
)

class RoundRepository(
    private val dao: RoundDao,
    private val clock: Clock = Clock.systemUTC(),
    /** Called after any change that may need uploading (asks the background sync to run). */
    private val onChanged: () -> Unit = {},
) {
    val rounds: Flow<List<RoundSummary>> = dao.observeRounds().map { list ->
        list.map {
            RoundSummary(
                id = it.id,
                shootDate = it.shootDate,
                targetCount = it.targetCount,
                classLine = "${it.archerClass.displayName} ${it.ageGroup.displayName} ${it.bowStyle.code}",
                totalPoints = it.totalPoints,
                status = it.status,
                syncState = it.syncState,
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
    ): String {
        require(targetCount in 1..MAX_TARGETS) { "Targets must be 1 to $MAX_TARGETS" }
        val now = Instant.now(clock)
        val id = UUID.randomUUID().toString()
        val targets = (1..targetCount).map { TargetScore(it) }
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
            ),
            targets = targets.map { it.toEntity(id, now) },
            animals = targets.flatMap { it.toAnimalEntities(id) },
        )
        return id
    }

    suspend fun saveTarget(roundId: String, target: TargetScore) {
        dao.saveTarget(target.toEntity(roundId, Instant.now(clock)), target.toAnimalEntities(roundId))
        onChanged()
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
    }
}

// ---- Mapping ----

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
        targets = targets.map { t ->
            TargetScore(
                targetNo = t.targetNo,
                type = t.type,
                animals = byTarget[t.targetNo].orEmpty().sortedBy { it.animalIndex }.map { it.toResult() },
                overridePoints = t.overridePoints,
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
