package uk.co.fieldarcheryscoring.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import uk.co.fieldarcheryscoring.domain.AgeGroup
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.domain.Hit
import uk.co.fieldarcheryscoring.domain.RoundStatus
import uk.co.fieldarcheryscoring.domain.TargetType
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

/**
 * Phone-side progress of a round's shoot location (null = no position). 3.2.25: PENDING_NAME = named with no signal,
 * made on the server when there's signal (round.pending_location); CHECK_NAME = when it was sent, the server knew
 * locations that might be the same one: the archer picks one or keeps theirs.
 */
enum class LocationState { PENDING_LOOKUP, NEEDS_NAME, SET, PENDING_NAME, CHECK_NAME }

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
    /** The archer's notes (database v8, app 3.2.21): own rounds only; synced, but only ever shown to the archer. */
    @ColumnInfo(name = "notes") val notes: String? = null,
    /** The bow and arrows it was shot with (database v9, app 3.2.24): own rounds only; synced, only shown to the archer. */
    @ColumnInfo(name = "bow_id") val bowId: String? = null,
    @ColumnInfo(name = "arrow_id") val arrowId: String? = null,
    /**
     * 3.2.25: a location named with no signal, waiting to be made on the server (JSON: name, private, noPin, the
     * position, locations the archer said it isn't, and the server's suggestions). PHONE ONLY — never sent with the round.
     */
    @ColumnInfo(name = "pending_location") val pendingLocation: String? = null,
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

/**
 * Weather for a round (database v7, app 3.2.17): looked up by the server (Open-Meteo, from the shoot location's map pin)
 * and downloaded. Its own table, so editing or downloading a round never loses it. Removed when the device's session is
 * cleared (another account signs in) and downloaded again.
 */
@Entity(tableName = "round_weather")
data class RoundWeatherEntity(
    @PrimaryKey @ColumnInfo(name = "round_id") val roundId: String,
    @ColumnInfo(name = "temp_c") val tempC: Double? = null,
    @ColumnInfo(name = "wind_kph") val windKph: Double? = null,
    @ColumnInfo(name = "gust_kph") val gustKph: Double? = null,
    @ColumnInfo(name = "rain_mm") val rainMm: Double? = null,
    val code: Int? = null,
)

/**
 * A bow (database v9, app 3.2.24): synced with the account, private to the archer. [bowStyles] = bow style codes,
 * comma-separated. Deleted ones are kept (flagged) so rounds shot with them still show their name. Put away with the
 * rounds when the archer signs out ([hiddenOwner]).
 */
@Entity(tableName = "bow")
data class BowEntity(
    @PrimaryKey val id: String,
    val type: String,
    val make: String? = null,
    val model: String? = null,
    @ColumnInfo(name = "poundage_lb") val poundageLb: Double? = null,
    @ColumnInfo(name = "bow_styles") val bowStyles: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.PENDING,
    val deleted: Boolean = false,
    @ColumnInfo(name = "hidden_owner") val hiddenOwner: String? = null,
)

/** A set of arrows (database v9, app 3.2.24), used with the bows in [bowIds] (comma-separated). As [BowEntity]. */
@Entity(tableName = "arrow_set")
data class ArrowSetEntity(
    @PrimaryKey val id: String,
    val make: String? = null,
    val model: String? = null,
    @ColumnInfo(name = "length_in") val lengthIn: Double? = null,
    @ColumnInfo(name = "arrow_grains") val arrowGrains: Double? = null,
    val spine: String? = null,
    @ColumnInfo(name = "fletch_count") val fletchCount: Int? = null,
    @ColumnInfo(name = "fletch_material") val fletchMaterial: String? = null,
    @ColumnInfo(name = "fletch_length_in") val fletchLengthIn: Double? = null,
    @ColumnInfo(name = "fletch_colours") val fletchColours: String? = null,
    @ColumnInfo(name = "point_grains") val pointGrains: Double? = null,
    @ColumnInfo(name = "bow_ids") val bowIds: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.PENDING,
    val deleted: Boolean = false,
    @ColumnInfo(name = "hidden_owner") val hiddenOwner: String? = null,
)
