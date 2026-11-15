package uk.co.fieldarcheryscoring.domain

// Making a location private or public from its page (3.2.12, Pete): only the archer who named it. Making it private when
// other archers have shot there takes it off their scorecards, so the app warns and asks first. The same text as the
// web app's domain/locationPrivacy.ts. Tested in LocationPrivacyTest.kt.

private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

/** The warning before making a location private, or null when nobody else has shot there (no need to ask). */
fun makePrivateWarning(name: String, otherArchers: Int, otherRounds: Int): String? {
    if (otherRounds <= 0) return null
    val archers = maxOf(1, otherArchers)
    return "${count(archers, "other archer")} ${if (archers == 1) "has" else "have"} ${count(otherRounds, "round")} at $name. " +
        "Making it private takes it off their ${if (otherRounds == 1) "scorecard" else "scorecards"}, " +
        "and it won't go back on if you make it public again."
}
