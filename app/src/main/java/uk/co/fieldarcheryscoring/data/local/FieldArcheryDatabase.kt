package uk.co.fieldarcheryscoring.data.local

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

    /** Signing out clears the phone (3.2.02). */
    @Query("DELETE FROM profile")
    suspend fun clear()

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
            "FROM round r WHERE r.deleted = 0 AND r.hidden_owner IS NULL AND (r.group_role IS NULL OR r.group_role = 'SCORER') " +
            "ORDER BY r.shoot_date DESC, r.created_at DESC",
    )
    abstract fun observeRounds(): Flow<List<RoundWithGroupSize>>

    // ---- History and stats (app 2.2): every finished round of your own, with targets and animals ----

    @Query("SELECT * FROM round WHERE $OWN_FINISHED")
    abstract fun observeOwnFinished(): Flow<List<RoundEntity>>

    @Query("SELECT * FROM round WHERE $OWN_FINISHED")
    abstract suspend fun ownFinishedRounds(): List<RoundEntity>

    @Query("SELECT t.* FROM target t JOIN round r ON r.id = t.round_id WHERE $OWN_FINISHED_R ORDER BY t.round_id, t.target_no")
    abstract suspend fun ownFinishedTargets(): List<TargetEntity>

    @Query(
        "SELECT a.* FROM animal a JOIN round r ON r.id = a.round_id WHERE $OWN_FINISHED_R " +
            "ORDER BY a.round_id, a.target_no, a.animal_index",
    )
    abstract suspend fun ownFinishedAnimals(): List<AnimalEntity>

    /** All your finished rounds in three queries (not three per round), read together so they're consistent. */
    @Transaction
    open suspend fun loadOwnFinished(): List<RoundData> {
        val targets = ownFinishedTargets().groupBy { it.roundId }
        val animals = ownFinishedAnimals().groupBy { it.roundId }
        return ownFinishedRounds().map { RoundData(it, targets[it.id].orEmpty(), animals[it.id].orEmpty()) }
    }

    // ---- Groups scored on this phone (database v4) ----

    @Query("SELECT * FROM round WHERE group_id = :groupId AND deleted = 0 AND hidden_owner IS NULL ORDER BY group_order")
    abstract fun observeGroup(groupId: String): Flow<List<RoundEntity>>

    @Query("SELECT * FROM round WHERE group_id = :groupId AND deleted = 0 AND hidden_owner IS NULL ORDER BY group_order")
    abstract suspend fun getGroup(groupId: String): List<RoundEntity>

    /** Live groups: a member's role or place in the order changed (e.g. a friend joined on their own phone). */
    @Query("UPDATE round SET group_role = :role, group_name = :name, group_order = :order WHERE id = :id")
    abstract suspend fun setGroupMember(id: String, role: String, name: String?, order: Int)

    /** Friends' rounds to send as requests (finished) or withdraw (deleted). */
    @Query(
        "SELECT id FROM round WHERE group_role = 'FRIEND' AND sync_state = 'PENDING' AND hidden_owner IS NULL " +
            "AND (deleted = 1 OR status = 'COMPLETED') ORDER BY updated_at",
    )
    abstract suspend fun pendingOfferIds(): List<String>

    /** Guests' scores and copies of other phones' cards (live groups) are temporary: gone 30 days after the round started. */
    @Query("DELETE FROM round WHERE group_role IN ('GUEST', 'MIRROR') AND created_at < :cutoffMillis")
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

    // ---- Whose rounds are on this phone (3.2.02; 3.2.09: put away, not wiped) ----

    /** Your own rounds showing (not friends', guests' or copies of other phones' cards), in progress or finished. */
    @Query("SELECT COUNT(*) FROM round WHERE deleted = 0 AND hidden_owner IS NULL AND (group_role IS NULL OR group_role = 'SCORER')")
    abstract suspend fun ownRoundCount(): Int

    /** Your own rounds still being scored (they're only sent to the server once finished). */
    @Query("SELECT COUNT(*) FROM round WHERE deleted = 0 AND hidden_owner IS NULL AND status = 'IN_PROGRESS' AND (group_role IS NULL OR group_role = 'SCORER')")
    abstract suspend fun inProgressCount(): Int

    /** Every showing round of your own goes up again (rounds added to an account at sign-in). */
    @Query("UPDATE round SET sync_state = 'PENDING' WHERE deleted = 0 AND hidden_owner IS NULL AND (group_role IS NULL OR group_role = 'SCORER')")
    abstract suspend fun markOwnPending()

    /** Signing out: every showing round is put away for [owner]. */
    @Query("UPDATE round SET hidden_owner = :owner WHERE hidden_owner IS NULL")
    abstract suspend fun hideShowing(owner: String)

    /** Signing in: [owner]'s rounds come back. */
    @Query("UPDATE round SET hidden_owner = NULL WHERE hidden_owner = :owner")
    abstract suspend fun showHidden(owner: String)

    /** The showing rounds go (their targets and animals with them). */
    @Query("DELETE FROM round WHERE hidden_owner IS NULL")
    abstract suspend fun deleteShowing()

    /** [owners]' put-away rounds go. */
    @Query("DELETE FROM round WHERE hidden_owner IN (:owners)")
    abstract suspend fun deleteHidden(owners: List<String>)

    /** How many of their own rounds each put-away account has here. */
    @Query(
        "SELECT hidden_owner AS owner, COUNT(*) AS rounds FROM round WHERE hidden_owner IS NOT NULL AND deleted = 0 " +
            "AND (group_role IS NULL OR group_role = 'SCORER') GROUP BY hidden_owner",
    )
    abstract suspend fun hiddenCounts(): List<OwnerCount>

    /** Your finished rounds marked as backed up (checked against the server's list once a day). */
    @Query("SELECT id FROM round WHERE deleted = 0 AND hidden_owner IS NULL AND status = 'COMPLETED' AND sync_state = 'SYNCED' AND (group_role IS NULL OR group_role = 'SCORER')")
    abstract suspend fun backedUpIds(): List<String>

    @Query("UPDATE round SET sync_state = 'PENDING' WHERE id IN (:ids)")
    abstract suspend fun markPending(ids: List<String>)

    /** Rounds waiting to go to the server: finished rounds and deletions (in-progress rounds stay on the phone). */
    @Query(
        "SELECT id FROM round WHERE sync_state = 'PENDING' AND hidden_owner IS NULL AND (deleted = 1 OR status = 'COMPLETED') " +
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
    @Query("SELECT * FROM round WHERE location_state = 'PENDING_LOOKUP' AND deleted = 0 AND hidden_owner IS NULL AND fix_lat IS NOT NULL")
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

/** An account with rounds put away on this phone, and how many of its own. */
data class OwnerCount(val owner: String, val rounds: Int)

data class RoundData(
    val round: RoundEntity,
    val targets: List<TargetEntity>,
    val animals: List<AnimalEntity>,
)

@Database(
    entities = [ProfileEntity::class, RoundEntity::class, TargetEntity::class, AnimalEntity::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FieldArcheryDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun roundDao(): RoundDao

    companion object {
        fun create(context: Context): FieldArcheryDatabase =
            Room.databaseBuilder(context, FieldArcheryDatabase::class.java, "fieldarchery.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        /** v6: rounds put away for a signed-out account (app 3.2.09). Existing rounds are all showing. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN hidden_owner TEXT")
            }
        }

        /** v5: the target a round started on (app 2.7). Existing rounds have none. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN start_target INTEGER")
            }
        }

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

/** Your own finished rounds (not friends' or guests' in a group, nor copies of other phones' cards). */
private const val OWN_FINISHED = "deleted = 0 AND hidden_owner IS NULL AND status = 'COMPLETED' AND (group_role IS NULL OR group_role = 'SCORER')"
private const val OWN_FINISHED_R = "r.deleted = 0 AND r.hidden_owner IS NULL AND r.status = 'COMPLETED' AND (r.group_role IS NULL OR r.group_role = 'SCORER')"
