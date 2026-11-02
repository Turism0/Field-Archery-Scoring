package uk.co.fnurble.nfas.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /**
     * This archer's own rounds (not ones scored for friends or guests in a group), with the size of their group
     * (0 = not a group round).
     */
    @Query(
        "SELECT r.*, (SELECT COUNT(*) FROM round g WHERE g.group_id = r.group_id AND g.deleted = 0) AS group_size, " +
            "(SELECT COUNT(*) FROM animal a WHERE a.round_id = r.id) AS animal_count, " +
            "(SELECT COUNT(*) FROM target t WHERE t.round_id = r.id AND t.type = 'PREDATOR_PREY') AS predator_prey_count, " +
            "(SELECT COUNT(*) FROM target t WHERE t.round_id = r.id AND t.type = 'FAN') AS fan_count " +
            "FROM round r WHERE r.deleted = 0 AND (r.group_role IS NULL OR r.group_role = 'SCORER') " +
            "ORDER BY r.shoot_date DESC, r.created_at DESC",
    )
    abstract fun observeRounds(): Flow<List<RoundWithGroupSize>>

    // ---- Groups scored on this phone (database v4) ----

    @Query("SELECT * FROM round WHERE group_id = :groupId AND deleted = 0 ORDER BY group_order")
    abstract fun observeGroup(groupId: String): Flow<List<RoundEntity>>

    @Query("SELECT * FROM round WHERE group_id = :groupId AND deleted = 0 ORDER BY group_order")
    abstract suspend fun getGroup(groupId: String): List<RoundEntity>

    /** Friends' rounds to send as requests (finished) or withdraw (deleted). */
    @Query(
        "SELECT id FROM round WHERE group_role = 'FRIEND' AND sync_state = 'PENDING' " +
            "AND (deleted = 1 OR status = 'COMPLETED') ORDER BY updated_at",
    )
    abstract suspend fun pendingOfferIds(): List<String>

    /** Guests' scores are temporary: gone 30 days after the round was started. */
    @Query("DELETE FROM round WHERE group_role = 'GUEST' AND created_at < :cutoffMillis")
    abstract suspend fun deleteGuestsBefore(cutoffMillis: Long): Int

    /** Starts a group round: everyone's round with all its targets, in one go. */
    @Transaction
    open suspend fun createRounds(rounds: List<RoundData>) {
        rounds.forEach { createRound(it.round, it.targets, it.animals) }
    }

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
    @Query(
        "SELECT id FROM round WHERE sync_state = 'PENDING' AND (deleted = 1 OR status = 'COMPLETED') " +
            "AND (group_role IS NULL OR group_role = 'SCORER') ORDER BY updated_at",
    )
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

    // ---- Editing round details ----

    @Query("DELETE FROM animal WHERE round_id = :roundId AND target_no > :count")
    abstract suspend fun deleteAnimalsAbove(roundId: String, count: Int)

    @Query("DELETE FROM target WHERE round_id = :roundId AND target_no > :count")
    abstract suspend fun deleteTargetsAbove(roundId: String, count: Int)

    /**
     * New date / class / age group / bow style / number of targets for a round: targets above the new count
     * go, new ones are added unscored, and the stored total is worked out again.
     */
    @Transaction
    open suspend fun updateDetails(round: RoundEntity, addedTargets: List<TargetEntity>, addedAnimals: List<AnimalEntity>) {
        deleteAnimalsAbove(round.id, round.targetCount)
        deleteTargetsAbove(round.id, round.targetCount)
        addedTargets.forEach { upsertTarget(it) }
        upsertAnimals(addedAnimals)
        upsertRound(round.copy(totalPoints = sumPoints(round.id)))
    }

    // ---- Shoot locations ----

    /** Position taken at the start of a round; looked up later. Doesn't count as an edit. */
    @Query("UPDATE round SET fix_lat = :lat, fix_lon = :lon, location_state = 'PENDING_LOOKUP' WHERE id = :id AND location_id IS NULL")
    abstract suspend fun setFix(id: String, lat: Double, lon: Double)

    /** Rounds with a position still to look up. */
    @Query("SELECT * FROM round WHERE location_state = 'PENDING_LOOKUP' AND deleted = 0 AND fix_lat IS NOT NULL")
    abstract suspend fun pendingLocationLookups(): List<RoundEntity>

    @Query("UPDATE round SET location_state = 'NEEDS_NAME' WHERE id = :id AND location_state = 'PENDING_LOOKUP'")
    abstract suspend fun markNeedsName(id: String)

    /**
     * Sets the round's location as an edit, so a finished round goes up to the server again. In a group, everyone's
     * round gets it (they're all at the same shoot).
     */
    @Query(
        "UPDATE round SET location_id = :locationId, location_name = :name, location_state = 'SET', " +
            "updated_at = :nowMillis, sync_state = 'PENDING' " +
            "WHERE id = :id OR (group_id IS NOT NULL AND group_id = (SELECT group_id FROM round WHERE id = :id))",
    )
    abstract suspend fun setLocation(id: String, locationId: String, name: String, nowMillis: Long)

    @Query("SELECT DISTINCT location_id FROM round WHERE location_id IS NOT NULL")
    abstract suspend fun locationIds(): List<String>

    /** New name (renamed) or new id (merged) from the server. Not an edit: the server already knows. */
    @Query("UPDATE round SET location_id = :newId, location_name = :name WHERE location_id = :oldId")
    abstract suspend fun refreshLocation(oldId: String, newId: String, name: String)

    @Transaction
    open suspend fun createRound(round: RoundEntity, targets: List<TargetEntity>, animals: List<AnimalEntity>) {
        upsertRound(round)
        targets.forEach { upsertTarget(it) }
        upsertAnimals(animals)
    }
}

/** A round with the number of rounds in its group (0 when it isn't a group round). */
data class RoundWithGroupSize(
    @Embedded val round: RoundEntity,
    @ColumnInfo(name = "group_size") val groupSize: Int,
    /** Big Game: animals on the round's targets (predator/prey = 2, fan = n). */
    @ColumnInfo(name = "animal_count") val animalCount: Int = 0,
    @ColumnInfo(name = "predator_prey_count") val predatorPreyCount: Int = 0,
    @ColumnInfo(name = "fan_count") val fanCount: Int = 0,
)

data class RoundData(
    val round: RoundEntity,
    val targets: List<TargetEntity>,
    val animals: List<AnimalEntity>,
)

@Database(
    entities = [ProfileEntity::class, RoundEntity::class, TargetEntity::class, AnimalEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfasDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun roundDao(): RoundDao

    companion object {
        fun create(context: Context): NfasDatabase =
            Room.databaseBuilder(context, NfasDatabase::class.java, "nfas.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** v4: groups scored on this phone (app 2.0). Existing rounds aren't in a group. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN group_id TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN group_role TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN group_name TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN group_archer_id TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN group_order INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_round_group_id ON round (group_id)")
            }
        }

        /** v3: round types (app 1.9). Existing rounds are Big Game (null type); Woodsman targets keep their arrows. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN round_type TEXT")
                db.execSQL("ALTER TABLE target ADD COLUMN arrows TEXT")
            }
        }

        /** v2: shoot location on each round (app 1.6). Existing rounds get no location. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN location_id TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN location_name TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN location_state TEXT")
                db.execSQL("ALTER TABLE round ADD COLUMN fix_lat REAL")
                db.execSQL("ALTER TABLE round ADD COLUMN fix_lon REAL")
            }
        }
    }
}
