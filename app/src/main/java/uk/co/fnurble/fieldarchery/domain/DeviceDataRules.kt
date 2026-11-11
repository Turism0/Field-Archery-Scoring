package uk.co.fnurble.fieldarchery.domain

/**
 * Whose rounds are on this phone (3.2.02, Pete): signing out clears the phone, and signing in decides what happens to
 * rounds already here. The same rules as the web app's domain/deviceData.ts.
 */
object DeviceDataRules {

    /**
     * What signing in does with the rounds already on this phone.
     * KEEP: they're this account's (or there are none). CLEAR: another account's, left by a sign-in that ended without
     * signing out (the server ended it); removed without asking. ASK: scored while nobody was signed in; the archer chooses.
     */
    enum class SignInData { KEEP, CLEAR, ASK }

    /** [owner] = the account the phone's rounds belong to (null when nobody was signed in); [localRounds] = your own rounds here. */
    fun signInData(owner: String?, archerId: String, localRounds: Int): SignInData = when {
        owner != null -> if (owner == archerId) SignInData.KEEP else SignInData.CLEAR
        localRounds > 0 -> SignInData.ASK
        else -> SignInData.KEEP
    }

    /** The sign-out dialog's warning ([waiting] changes not yet sent, rounds still [inProgress]), or null when nothing would be lost. */
    fun signOutWarning(waiting: Int, inProgress: Int): String? {
        val parts = buildList {
            if (waiting > 0) add("$waiting change${if (waiting == 1) " hasn't" else "s haven't"} been backed up yet (it needs signal)")
            if (inProgress > 0) add("$inProgress round${if (inProgress == 1) " is" else "s are"} still in progress (rounds are backed up once finished)")
        }
        if (parts.isEmpty()) return null
        return "${parts.joinToString(", and ")}. Signing out now loses ${if (waiting + inProgress == 1) "it" else "them"}."
    }
}
