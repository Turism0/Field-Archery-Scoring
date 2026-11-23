package uk.co.fieldarcheryscoring.domain

/**
 * 3.2.25 (Pete): people were making duplicate shoot locations, and with no signal you couldn't pick one at all. Locations
 * you can use are kept on the phone ([KnownPlace], refreshed on each sync) so they can be found with no signal, and a new
 * name is checked against them first: the same or a similar name (anywhere), or within [SIMILAR_METRES]. The name rules
 * are the server's (DuplicateFinder.Key / Similar), so the phone and the API agree. The same as the web app's
 * domain/locationMatch.ts.
 */
data class KnownPlace(val id: String, val name: String, val latitude: Double?, val longitude: Double?, val isPrivate: Boolean = false) {
    val hasPin: Boolean get() = latitude != null && longitude != null
}

/** A location that might be the one being named. [reasons]: same_name, similar_name, nearby. */
data class PlaceMatch(val id: String, val name: String, val isPrivate: Boolean, val distanceMetres: Int?, val reasons: List<String>)

object LocationMatch {
    const val SIMILAR_METRES = 300
    /** The server's catchment: a round within this of a location is put at it without asking (about a quarter mile). */
    const val CATCHMENT_METRES = 402

    private val commonWords = setOf(
        "the", "of", "and", "at", "club", "clubs", "archers", "archery", "bowmen", "bowman", "field", "fa", "fac",
        "society", "group", "ground", "grounds", "company", "co", "ltd", "nfas", "target", "targets", "range", "wood", "woods",
    )

    /** "The Bowmen of Foo-Bar!" → "foobar". All the words if they're all common ones. */
    fun key(name: String): String {
        val words = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in name.lowercase() + " ") {
            if (c.isLetterOrDigit()) { sb.append(c); continue }
            if (c == '-' || c == '\'' || c == '’') continue
            if (sb.isNotEmpty()) { words += sb.toString(); sb.clear() }
        }
        val significant = words.filter { it !in commonWords }
        return (significant.ifEmpty { words }).joinToString("")
    }

    /** One contains the other (4+ letters), or at least 80% the same by edit distance. */
    fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (minOf(a.length, b.length) >= 4 && (a.contains(b) || b.contains(a))) return true
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length) >= 0.8
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Places in [known] that might be the one being named [name] (at [lat], [lon] if it has a pin), best first, at most 5.
     * [skip]: ones the archer has already said it isn't.
     */
    fun matches(name: String, lat: Double?, lon: Double?, known: List<KnownPlace>, skip: Set<String> = emptySet()): List<PlaceMatch> {
        val k = key(name)
        return known.filter { it.id !in skip }.mapNotNull { p ->
            val metres = if (lat != null && lon != null && p.hasPin) Math.round(metresBetween(lat, lon, p.latitude!!, p.longitude!!)).toInt() else null
            val reasons = buildList {
                val pk = key(p.name)
                if (pk == k) add("same_name") else if (similar(pk, k)) add("similar_name")
                if (metres != null && metres <= SIMILAR_METRES) add("nearby")
            }
            if (reasons.isEmpty()) null else PlaceMatch(p.id, p.name, p.isPrivate, metres, reasons)
        }.sortedWith(compareBy<PlaceMatch>({ -it.reasons.size }, { it.distanceMetres ?: Int.MAX_VALUE }, { it.name })).take(5)
    }

    /** Places with a pin within [radiusMetres] of a position, nearest first (with no signal, from the phone's copy). */
    fun nearby(known: List<KnownPlace>, lat: Double, lon: Double, radiusMetres: Int): List<NearbyLocation> =
        known.filter { it.hasPin }
            .map { NearbyLocation(it.id, it.name, Math.round(metresBetween(lat, lon, it.latitude!!, it.longitude!!)).toInt()) }
            .filter { it.distanceMetres <= radiusMetres }
            .sortedBy { it.distanceMetres }

    /** "Same name", "Similar name", "250 m away" for a suggestion. */
    fun why(m: PlaceMatch): String = listOfNotNull(
        when { "same_name" in m.reasons -> "Same name"; "similar_name" in m.reasons -> "Similar name"; else -> null },
        m.distanceMetres?.let {
            when {
                it < 1000 -> "$it m away"
                it < 10_000 -> String.format(java.util.Locale.UK, "%.1f km away", it / 1000.0)
                else -> "${Math.round(it / 1000.0)} km away"
            }
        },
        if (m.isPrivate) "your private location" else null,
    ).joinToString(" · ")
}
