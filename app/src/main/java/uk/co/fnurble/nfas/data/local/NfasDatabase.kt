package uk.co.fnurble.nfas.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

class Converters {
    @TypeConverter fun localDateToString(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun stringToLocalDate(s: String?): LocalDate? = s?.let(LocalDate::parse)
    @TypeConverter fun instantToLong(i: Instant?): Long? = i?.toEpochMilli()
    @TypeConverter fun longToInstant(l: Long?): Instant? = l?.let(Instant::ofEpochMilli)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
abstract class RoundDao {
    @Query("SELECT * FROM round WHERE deleted = 0 ORDER BY shoot_date DESC, created_at DESC")
    abstract fun observeRounds(): Flow<List<RoundEntity>>

    @Query("SELECT * FROM round WHERE id = :id")
    abstract fun observeRound(id: String): Flow<RoundEntity?>

    @Query("SELECT * FROM round WHERE id = :id")
    abstract suspend fun getRound(id: String): RoundEntity?

    @Query("SELECT * FROM target WHERE round_id = :roundId ORDER BY target_no")
    abstract suspend fun getTargets(roundId: String): List<TargetEntity>

    @Query("SELECT * FROM animal WHERE round_id = :roundId ORDER BY target_no, animal_index")
    abstract suspend fun getAnimals(roundId: String): List<AnimalEntity>

    /** Reads a round, its targets and animals in one transaction so they are always consistent. */
    @Transaction
    open suspend fun loadRound(id: String): RoundData? {
        val round = getRound(id) ?: return null
        return RoundData(round, getTargets(id), getAnimals(id))
    }

    /** Rounds waiting to go to the server: finished rounds and deletions (in-progress rounds stay on the phone). */
    @Query("SELECT id FROM round WHERE sync_state = 'PENDING' AND (deleted = 1 OR status = 'COMPLETED') ORDER BY updated_at")
    abstract suspend fun pendingUploadIds(): List<String>

    /**
     * Records the upload result, but only if the round hasn't been edited since it was read
     * (otherwise it stays PENDING and goes up again with the newer edit). Returns rows changed.
     */
    @Query("UPDATE round SET sync_state = :state WHERE id = :id AND updated_at = :updatedAtMillis")
    abstract suspend fun markSync(id: String, updatedAtMillis: Long, state: String): Int

    @Query("SELECT COALESCE(SUM(points), 0) FROM target WHERE round_id = :roundId")
    abstract suspend fun sumPoints(roundId: String): Int

    @Upsert abstract suspend fun upsertRound(round: RoundEntity)
    @Upsert abstract suspend fun upsertTarget(target: TargetEntity)
    @Upsert abstract suspend fun upsertAnimals(animals: List<AnimalEntity>)

    @Query("DELETE FROM animal WHERE round_id = :roundId AND target_no = :targetNo")
    abstract suspend fun deleteAnimals(roundId: String, targetNo: Int)

    /** Saves a target and its animals, then refreshes the round's stored total. */
    @Transaction
    open suspend fun saveTarget(target: TargetEntity, animals: List<AnimalEntity>) {
        upsertTarget(target)
        deleteAnimals(target.roundId, target.targetNo) // animal count can change with target type
        upsertAnimals(animals)
        val round = getRound(target.roundId) ?: return
        upsertRound(
            round.copy(
                totalPoints = sumPoints(target.roundId),
                updatedAt = target.updatedAt,
                syncState = SyncState.PENDING,
            ),
        )
    }

    @Query("DELETE FROM target WHERE round_id = :roundId")
    abstract suspend fun deleteTargets(roundId: String)

    @Query("DELETE FROM animal WHERE round_id = :roundId")
    abstract suspend fun deleteAllAnimals(roundId: String)

    /** Replaces a whole round with a copy downloaded from the server. */
    @Transaction
    open suspend fun replaceRound(round: RoundEntity, targets: List<TargetEntity>, animals: List<AnimalEntity>) {
        upsertRound(round)
        deleteAllAnimals(round.id)
        deleteTargets(round.id)
        targets.forEach { upsertTarget(it) }
        upsertAnimals(animals)
    }

    @Transaction
    open suspend fun createRound(round: RoundEntity, targets: List<TargetEntity>, animals: List<AnimalEntity>) {
        upsertRound(round)
        targets.forEach { upsertTarget(it) }
        upsertAnimals(animals)
    }
}

data class RoundData(
    val round: RoundEntity,
    val targets: List<TargetEntity>,
    val animals: List<AnimalEntity>,
)

@Database(
    entities = [ProfileEntity::class, RoundEntity::class, TargetEntity::class, AnimalEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfasDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun roundDao(): RoundDao

    companion object {
        fun create(context: Context): NfasDatabase =
            Room.databaseBuilder(context, NfasDatabase::class.java, "nfas.db").build()
    }
}
