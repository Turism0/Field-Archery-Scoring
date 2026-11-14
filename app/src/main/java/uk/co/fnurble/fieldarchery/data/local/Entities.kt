package uk.co.fnurble.fieldarchery.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import uk.co.fnurble.fieldarchery.domain.AgeGroup
import uk.co.fnurble.fieldarchery.domain.ArcherClass
import uk.co.fnurble.fieldarchery.domain.BowStyle
import uk.co.fnurble.fieldarchery.domain.Hit
import uk.co.fnurble.fieldarchery.domain.RoundStatus
import uk.co.fnurble.fieldarchery.domain.TargetType
import java.time.Instant
import java.time.LocalDate

/** Sync state of a row. Iteration 2 uploads PENDING rows and marks them SYNCED. */
/** Upload state of a round. FAILED = the server refused it (e.g. another account's round); retried after the next edit. */
enum class SyncState { PENDING, SYNCED, FAILED }

enum class AnimalState { PENDING, MISSED, SCORED }

/**
 * A round's place in a group scored on this phone (database v4). Null = an ordinary round.
 * SCORER = this archer's own round in the group (in history and synced as normal); FRIEND = scored for a friend
 * (sent to them as a request, never in this archer's history); GUEST = scored for a guest (phone only, deleted
 * after 30 days). MIRROR (app 2.1, live groups) = someone with the group on their own phone: this phone keeps a copy of
 * their card to show and score it, but it's theirs (never in this archer's history, never uploaded; deleted after 30 days).
 */
enum class GroupRole { SCORER, FRIEND, GUEST, MIRROR }

/** Phone-side progress of a round's shoot location (null = no position). */
enum class LocationState { PENDING_LOOKUP, NEEDS_NAME, SET }

/**
 * The archer's profile. PHONE ONLY — never synced.
 * Holds personal data (real name, date of birth) that must not reach the server.
 * Single row, id = 1.
 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "real_name") val realName: String? = null,
    /** Public username used on the server. Must not be the archer's real name. */
    val username: String? = null,
    @ColumnInfo(name = "date_of_birth") val dateOfBirth: LocalDate? = null,
    @ColumnInfo(name = "archer_class") val archerClass: ArcherClass? = null,
    /** Manual age group; when null it's worked out from date of birth. */
    @ColumnInfo(name = "age_group_manual") val ageGroupManual: AgeGroup? = null,
    @ColumnInfo(name = "main_bow_style") val mainBowStyle: BowStyle? = null,
)

/**
 * A round. Ids are UUIDs made on the phone so the server never creates duplicates on retry.
 * Class, age group and bow style are copied in at the start so later profile changes don't
 * alter old rounds.
 */
@Entity(tableName = "round", indices = [Index("shoot_date"), Index("group_id")])
data class RoundEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "shoot_date") val shootDate: LocalDate,
    @ColumnInfo(name = "target_count") val targetCount: Int,
    @ColumnInfo(name = "archer_class") val archerClass: ArcherClass,
    @ColumnInfo(name = "age_group") val ageGroup: AgeGroup,
    @ColumnInfo(name = "bow_style") val bowStyle: BowStyle,
    val status: RoundStatus,
    /** Stored for fast lists and server reporting; recalculated whenever a target is saved. */
    @ColumnInfo(name = "total_points") val totalPoints: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.PENDING,
    /** Soft delete so the deletion can be synced to the server. */
    val deleted: Boolean = false,
    // ---- Shoot location (database v2) ----
    /** Server id of the shoot location, once matched or named. Synced. */
    @ColumnInfo(name = "location_id") val locationId: String? = null,
    @ColumnInfo(name = "location_name") val locationName: String? = null,
    @ColumnInfo(name = "location_state") val locationState: LocationState? = null,
    /**
     * GPS position taken when the round started. PHONE ONLY — never sent with the round; only used to
     * ask the server which location is nearby.
     */
    @ColumnInfo(name = "fix_lat") val fixLat: Double? = null,
    @ColumnInfo(name = "fix_lon") val fixLon: Double? = null,
    /** Round type (database v3): null = Big Game (every round from before v3), or a RoundType name. */
    @ColumnInfo(name = "round_type") val roundType: String? = null,
    // ---- Group scored on this phone (database v4). Every round in the group shares the group id. ----
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    @ColumnInfo(name = "group_role") val groupRole: GroupRole? = null,
    /** The friend's username or the guest's name (null for the scorer's own round). */
    @ColumnInfo(name = "group_name") val groupName: String? = null,
    /** The friend's server archer id (FRIEND only), so the round can be sent to them. */
    @ColumnInfo(name = "group_archer_id") val groupArcherId: String? = null,
    /** Scoring order in the group: 0 = the scorer. */
    @ColumnInfo(name = "group_order") val groupOrder: Int? = null,
    /** The target the round started on (database v5, app 2.7): a note for the scorecard; targets keep their numbers. */
    @ColumnInfo(name = "start_target") val startTarget: Int? = null,
    /**
     * Database v6 (3.2.09): put away for this account (signed out), hidden until it signs in here again. Null = showing.
     * PHONE ONLY — never sent with the round.
     */
    @ColumnInfo(name = "hidden_owner") val hiddenOwner: String? = null,
)

@Entity(
    tableName = "target",
    primaryKeys = ["round_id", "target_no"],
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["id"],
            childColumns = ["round_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TargetEntity(
    @ColumnInfo(name = "round_id") val roundId: String,
    @ColumnInfo(name = "target_no") val targetNo: Int,
    val type: TargetType,
    @ColumnInfo(name = "override_points") val overridePoints: Int?,
    /** Stored total (override or calculated). */
    val points: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.PENDING,
    /** Woodsman targets (database v3): arrows in order, W / K / M. Null for Big Game. */
    val arrows: String? = null,
)

@Entity(
    tableName = "animal",
    primaryKeys = ["round_id", "target_no", "animal_index"],
    foreignKeys = [
        ForeignKey(
            entity = TargetEntity::class,
            parentColumns = ["round_id", "target_no"],
            childColumns = ["round_id", "target_no"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AnimalEntity(
    @ColumnInfo(name = "round_id") val roundId: String,
    @ColumnInfo(name = "target_no") val targetNo: Int,
    /** 0-based. For predator/prey: 0 = predator, 1 = prey. */
    @ColumnInfo(name = "animal_index") val animalIndex: Int,
    val state: AnimalState,
    /** Arrow number at this animal that scored (1–3), null unless SCORED. */
    @ColumnInfo(name = "arrow_no") val arrowNo: Int?,
    val hit: Hit?,
    val points: Int,
)
