package uk.co.fnurble.fieldarchery.data

import android.content.Context
import uk.co.fnurble.fieldarchery.data.local.ProfileDao
import uk.co.fnurble.fieldarchery.data.local.RoundDao

/**
 * Whose rounds are on this phone (3.2.02, Pete). Signing out clears the phone so the next person starts clean; signing
 * in keeps the account's own rounds, removes another account's, and asks about rounds scored while nobody was signed in
 * (see domain/DeviceDataRules). Home's layout and the public handicap model belong to the phone and stay.
 */
class DeviceData(
    context: Context,
    private val rounds: RoundDao,
    private val profile: ProfileDao,
    private val statsFilter: StatsFilterStore,
    private val liveGroups: LiveGroupRepository,
) {
    /** Kept apart from the account's own file, which is cleared whenever the sign-in ends. */
    private val prefs = context.getSharedPreferences("device_data", Context.MODE_PRIVATE)

    /** The account this phone's rounds belong to; null when nobody was signed in while they were scored. */
    var owner: String?
        get() = prefs.getString(K_OWNER, null)
        set(value) { prefs.edit().apply { if (value == null) remove(K_OWNER) else putString(K_OWNER, value) }.apply() }

    suspend fun ownRoundCount(): Int = rounds.ownRoundCount()

    /** What signing out would lose: changes not yet sent, and rounds still in progress. */
    suspend fun signOutLoss(): Pair<Int, Int> =
        (rounds.pendingUploadIds().size + rounds.pendingOfferIds().size) to rounds.inProgressCount()

    /** Rounds kept when signing in all go up to the new account. */
    suspend fun claimRounds() = rounds.markOwnPending()

    /** Every round on the phone; with [everything], the profile, Stats filters, live groups and the owner too. */
    suspend fun clear(everything: Boolean) {
        rounds.clearRounds()
        if (!everything) return
        profile.clear()
        statsFilter.clear()
        liveGroups.clearAll()
        owner = null
    }

    private companion object {
        const val K_OWNER = "owner"
    }
}
