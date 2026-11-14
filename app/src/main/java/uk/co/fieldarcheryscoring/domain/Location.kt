package uk.co.fieldarcheryscoring.domain

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

/** Great-circle distance in metres (haversine), e.g. to list likely duplicates nearest first. */
fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
}
