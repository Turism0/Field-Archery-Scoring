package uk.co.fnurble.nfas.domain

/** Where a round was shot, as far as the phone knows. */
enum class LocationStatus {
    /** No GPS position (permission refused, no fix, or a round from before locations). */
    NONE,
    /** Position taken, not yet looked up (waiting for signal or sign-in). */
    LOOKING,
    /** Looked up: no known location nearby, so the archer is asked to name it. */
    NEEDS_NAME,
    /** Matched to a shoot location. */
    SET,
}

data class RoundLocation(val status: LocationStatus = LocationStatus.NONE, val name: String? = null)

/** A shoot location near the round's position, for "Not here?". */
data class NearbyLocation(val id: String, val name: String, val distanceMetres: Int)
