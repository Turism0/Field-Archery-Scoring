package uk.co.fieldarcheryscoring.data

import android.content.Context
import org.json.JSONObject
import uk.co.fieldarcheryscoring.data.local.ProfileDao
import uk.co.fieldarcheryscoring.data.local.ProfileEntity
import uk.co.fieldarcheryscoring.data.local.RoundDao
import uk.co.fieldarcheryscoring.domain.AgeGroup
import uk.co.fieldarcheryscoring.domain.ArcherClass
import uk.co.fieldarcheryscoring.domain.BowStyle
import uk.co.fieldarcheryscoring.domain.DeviceDataRules
import java.time.LocalDate

/**
 * Whose rounds are on this phone (3.2.02; 3.2.09, Pete: put away, not wiped). Each account's rounds stay on the phone:
 * signing out puts them away (hidden from anyone else), with the profile and Stats filters, and signing in as that
 * account brings them all back. Rounds scored while nobody was signed in, and an earlier account with the same
 * username (deleted, or the server lost it), are asked about at sign-in (see domain/DeviceDataRules).
 * Home's layout and the public handicap model belong to the phone and are never put away.
 */
class DeviceData(
    context: Context,
    private val rounds: RoundDao,
    private val profile: ProfileDao,
    private val statsFilter: StatsFilterStore,
    private val liveGroups: LiveGroupRepository,
) {
    /** Kept apart from the account's own file, which is cleared whenever the sign-in ends. Backed up with the rounds. */
    private val prefs = context.getSharedPreferences("device_data", Context.MODE_PRIVATE)

    /** The account the showing rounds belong to; null when nobody was signed in while they were scored. */
    var owner: String?
        get() = prefs.getString(K_OWNER, null)
        set(value) { prefs.edit().apply { if (value == null) remove(K_OWNER) else putString(K_OWNER, value) }.apply() }

    /** Remembers [archerId]'s username, so an earlier account with the same name can be recognised later. */
    fun rememberName(archerId: String, username: String) = prefs.edit().putString(K_NAME + archerId, username).apply()

    fun forgetName(archerId: String) = prefs.edit().remove(K_NAME + archerId).apply()

    /** What signing in as [archerId] / [username] does with the rounds here. */
    suspend fun planSignIn(archerId: String, username: String): DeviceDataRules.SignInPlan =
        DeviceDataRules.signInPlan(
            owner, archerId, username, rounds.ownRoundCount(),
            rounds.hiddenCounts().map { DeviceDataRules.HiddenOwner(it.owner, prefs.getString(K_NAME + it.owner, null).orEmpty(), it.rounds) },
        )

    /** What removing the showing rounds would lose: changes not yet sent, and rounds still in progress. */
    suspend fun signOutLoss(): Pair<Int, Int> =
        (rounds.pendingUploadIds().size + rounds.pendingOfferIds().size) to rounds.inProgressCount()

    /** Signing out (or the server ending the sign-in): [who]'s rounds, profile and Stats filters are put away here. */
    suspend fun putAway(who: String) {
        profile.get()?.let { prefs.edit().putString(K_PROFILE + who, it.toJson()).apply() }
        statsFilter.current()?.let { prefs.edit().putString(K_FILTER + who, it).apply() }
        rounds.hideShowing(who)
        clearSession()
        if (owner == who) owner = null
    }

    /** Signing in: [who]'s rounds, profile and Stats filters come back. */
    suspend fun bringBack(who: String) {
        rounds.showHidden(who)
        prefs.getString(K_PROFILE + who, null)?.let { profile.upsert(profileFromJson(it)) }
        prefs.getString(K_FILTER + who, null)?.let { statsFilter.restore(it) }
        prefs.edit().remove(K_PROFILE + who).remove(K_FILTER + who).apply()
    }

    /**
     * The answer at sign-in. [keep]: the signed-out rounds and the [earlier] accounts' rounds are added to this account
     * (and go up again; an earlier account's profile comes too unless this one has its own put away). Otherwise they're
     * removed from the phone.
     */
    suspend fun settle(earlier: List<String>, keep: Boolean, archerId: String) {
        if (keep) {
            for (id in earlier) {
                rounds.showHidden(id)
                if (!prefs.contains(K_PROFILE + archerId)) prefs.getString(K_PROFILE + id, null)?.let { prefs.edit().putString(K_PROFILE + archerId, it).apply() }
                if (!prefs.contains(K_FILTER + archerId)) prefs.getString(K_FILTER + id, null)?.let { prefs.edit().putString(K_FILTER + archerId, it).apply() }
            }
            rounds.markOwnPending()   // before this account's own rounds come back, so only these go up again
        } else {
            rounds.deleteShowing()
            if (earlier.isNotEmpty()) rounds.deleteHidden(earlier)
        }
        prefs.edit().apply { earlier.forEach { remove(K_PROFILE + it); remove(K_FILTER + it); remove(K_NAME + it) } }.apply()
    }

    /** Signing out with "Also remove my rounds from this phone": the showing rounds and this account's things go. */
    suspend fun removeShowing(who: String) {
        rounds.deleteShowing()
        prefs.edit().remove(K_PROFILE + who).remove(K_FILTER + who).apply()
        clearSession()
        if (owner == who) owner = null
    }

    /** What a sign-in keeps on the phone besides rounds: the profile, the Stats filters and live groups. */
    suspend fun clearSession() {
        profile.clear()
        statsFilter.clear()
        liveGroups.clearAll()
    }

    // ---- Profile (with real name and date of birth, which never leave the phone) kept in the prefs while put away ----

    private fun ProfileEntity.toJson(): String = JSONObject().apply {
        putOpt("realName", realName); putOpt("username", username); putOpt("dateOfBirth", dateOfBirth?.toString())
        putOpt("archerClass", archerClass?.name); putOpt("ageGroupManual", ageGroupManual?.name); putOpt("mainBowStyle", mainBowStyle?.name)
    }.toString()

    private fun profileFromJson(text: String): ProfileEntity = runCatching {
        val j = JSONObject(text)
        fun s(k: String) = if (j.isNull(k)) null else j.optString(k).ifEmpty { null }
        ProfileEntity(
            realName = s("realName"),
            username = s("username"),
            dateOfBirth = s("dateOfBirth")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            archerClass = s("archerClass")?.let { n -> ArcherClass.entries.firstOrNull { it.name == n } },
            ageGroupManual = s("ageGroupManual")?.let { n -> AgeGroup.entries.firstOrNull { it.name == n } },
            mainBowStyle = s("mainBowStyle")?.let { n -> BowStyle.entries.firstOrNull { it.name == n } },
        )
    }.getOrDefault(ProfileEntity())

    private companion object {
        const val K_OWNER = "owner"
        const val K_NAME = "name:"
        const val K_PROFILE = "profile:"
        const val K_FILTER = "filter:"
    }
}
