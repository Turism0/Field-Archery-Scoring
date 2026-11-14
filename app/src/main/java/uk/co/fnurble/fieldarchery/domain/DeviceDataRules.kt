package uk.co.fnurble.fieldarchery.domain

/**
 * Whose rounds are on this phone (3.2.02; 3.2.09, Pete: put away, not wiped). Each account's rounds stay on the
 * phone: signing out puts them away (hidden), and signing in as that account brings them back. The same rules as the
 * web app's domain/deviceData.ts.
 */
object DeviceDataRules {

    /** An account whose rounds are put away on this phone. */
    data class HiddenOwner(val archerId: String, val username: String, val rounds: Int)

    /** What signing in does with the rounds already on this phone. */
    data class SignInPlan(
        /** The rounds showing belong to another account (its sign-in ended without signing out): put them away first. */
        val hideFirst: String?,
        /** Rounds scored while nobody was signed in: asked about. */
        val signedOut: Int,
        /**
         * Earlier accounts with this username (a different account id: deleted, or the server lost it) whose rounds are
         * put away here: asked about, so they can be added to this account (and sent to the server again).
         */
        val earlier: List<String>,
        val earlierRounds: Int,
    ) {
        val needsAsking: Boolean get() = signedOut + earlierRounds > 0
        val total: Int get() = signedOut + earlierRounds
    }

    /**
     * [visibleOwner] = the account the showing rounds belong to (null: nobody was signed in); [visibleRounds] = your own
     * rounds showing; [hidden] = accounts with rounds put away.
     */
    fun signInPlan(visibleOwner: String?, archerId: String, username: String, visibleRounds: Int, hidden: List<HiddenOwner>): SignInPlan {
        val hideFirst = visibleOwner?.takeIf { it != archerId }
        // With the showing rounds put away first, they count as an earlier account's (below), not as signed-out rounds.
        val all = if (hideFirst == null) hidden else hidden.filter { it.archerId != hideFirst } +
            HiddenOwner(hideFirst, hidden.firstOrNull { it.archerId == hideFirst }?.username.orEmpty(), visibleRounds)
        val earlier = all.filter { it.archerId != archerId && it.rounds > 0 && it.username.equals(username, ignoreCase = true) }
        return SignInPlan(
            hideFirst = hideFirst,
            signedOut = if (visibleOwner != null) 0 else visibleRounds,
            earlier = earlier.map { it.archerId },
            earlierRounds = earlier.sumOf { it.rounds },
        )
    }

    /** The question, e.g. "There are 3 rounds on this phone scored while nobody was signed in. Add them to pete's account…". */
    fun signInQuestion(p: SignInPlan, username: String): String {
        fun rounds(n: Int) = if (n == 1) "1 round" else "$n rounds"
        val them = if (p.total == 1) "it" else "them"
        val what = when {
            p.signedOut > 0 && p.earlierRounds > 0 ->
                "${rounds(p.total)} on this phone that aren't on this account: ${rounds(p.signedOut)} scored while nobody was signed in, " +
                    "and ${rounds(p.earlierRounds)} from an earlier account called $username (the server no longer has it)"
            p.signedOut > 0 -> "${rounds(p.signedOut)} on this phone scored while nobody was signed in"
            else -> "${rounds(p.earlierRounds)} on this phone from an earlier account called $username (the server no longer has it)"
        }
        return "There ${if (p.total == 1) "is" else "are"} $what. Add $them to $username's account " +
            "(${if (them == "it") "it's" else "they're"} backed up again), or remove $them from this phone?"
    }

    /** The warning under "Also remove my rounds from this phone" ([waiting] changes not yet sent, rounds still [inProgress]); null when nothing would be lost. */
    fun signOutWarning(waiting: Int, inProgress: Int): String? {
        val parts = buildList {
            if (waiting > 0) add("$waiting change${if (waiting == 1) " hasn't" else "s haven't"} been backed up yet (it needs signal)")
            if (inProgress > 0) add("$inProgress round${if (inProgress == 1) " is" else "s are"} still in progress (rounds are backed up once finished)")
        }
        if (parts.isEmpty()) return null
        return "${parts.joinToString(", and ")}. Removing your rounds now loses ${if (waiting + inProgress == 1) "it" else "them"}."
    }

    /**
     * Rounds the server is missing (3.2.09): of your finished rounds marked as backed up ([backedUp]), those whose ids the
     * server doesn't have (it lost its data, or was restored from an older backup). They're sent again.
     */
    fun missingOnServer(backedUp: List<String>, serverIds: Set<String>): List<String> = backedUp.filter { it !in serverIds }
}
