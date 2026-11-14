package uk.co.fieldarcheryscoring.domain

/** The tiles on Home (app 2.4.01), in their usual order. */
enum class HomeTileId(val title: String) {
    SCORE("Score a round"),
    HISTORY("Score history"),
    STATS("Stats"),
    HANDICAP("Handicaps"),
    FRIENDS("Friends"),
    LOCATIONS("Shoot locations"),
    ADMIN("Admin"),
    ;

    /** Score a round is always on Home. */
    val canHide: Boolean get() = this != SCORE
}

/**
 * How an archer has arranged Home (2.4.01, Pete): the tiles' order and any they've hidden. Kept on the phone only.
 * Tiles added in later versions go in at their usual place.
 */
data class HomeLayout(
    val order: List<HomeTileId> = HomeTileId.entries.toList(),
    val hidden: Set<HomeTileId> = emptySet(),
) {
    /** Every tile once, new ones at their usual place; Score a round never hidden. */
    fun normalised(): HomeLayout {
        val known = order.distinct().filter { it in HomeTileId.entries }.toMutableList()
        for (id in HomeTileId.entries) {
            if (id in known) continue
            // After the tile that usually comes before it (or first).
            val before = HomeTileId.entries.take(id.ordinal).lastOrNull { it in known }
            known.add(if (before == null) 0 else known.indexOf(before) + 1, id)
        }
        return HomeLayout(known, hidden.filter { it.canHide }.toSet())
    }

    /** Moves [id] [by] places (−1 up, +1 down) among [shown] (the tiles this archer can see, e.g. Admin only for admins). */
    fun move(id: HomeTileId, by: Int, shown: Collection<HomeTileId> = HomeTileId.entries): HomeLayout {
        val visible = order.filter { it in shown }
        val i = visible.indexOf(id)
        val j = i + by
        if (i < 0 || j !in visible.indices) return this
        val other = visible[j]
        // Swap the two in the full order, so tiles not shown keep their places.
        val full = order.toMutableList()
        val a = full.indexOf(id)
        val b = full.indexOf(other)
        full[a] = other
        full[b] = id
        return copy(order = full)
    }

    fun setHidden(id: HomeTileId, hide: Boolean): HomeLayout =
        if (!id.canHide) this else copy(hidden = if (hide) hidden + id else hidden - id)

    val isDefault: Boolean get() = normalised() == HomeLayout()

    /** "SCORE,STATS,…|HANDICAP" for storing. */
    fun encode(): String = order.joinToString(",") { it.name } + "|" + hidden.joinToString(",") { it.name }

    companion object {
        fun decode(text: String?): HomeLayout {
            if (text.isNullOrBlank()) return HomeLayout()
            val (o, h) = (text.split("|", limit = 2) + "").let { it[0] to it[1] }
            fun ids(s: String) = s.split(",").mapNotNull { n -> HomeTileId.entries.firstOrNull { it.name == n.trim() } }
            return HomeLayout(ids(o), ids(h).toSet()).normalised()
        }
    }
}
