package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arranging Home (2.4.01). */
class HomeLayoutTest {
    private val all = HomeTileId.entries

    @Test
    fun usualOrderByDefault() {
        assertEquals(all, HomeLayout().order)
        assertTrue(HomeLayout().isDefault)
    }

    @Test
    fun moveUpAndDown() {
        val l = HomeLayout().move(HomeTileId.STATS, -1)
        assertEquals(listOf(HomeTileId.SCORE, HomeTileId.STATS, HomeTileId.HISTORY), l.order.take(3))
        assertEquals(l, l.move(HomeTileId.SCORE, -1))   // already first
        assertEquals(HomeLayout().order, l.move(HomeTileId.STATS, 1).order)
        assertFalse(l.isDefault)
    }

    @Test
    fun movingSkipsTilesNotShown() {
        // Not an admin: Admin isn't shown, so Shoot locations is already last.
        val shown = all - HomeTileId.ADMIN
        val l = HomeLayout().move(HomeTileId.LOCATIONS, 1, shown)
        assertEquals(HomeLayout(), l)
        val up = HomeLayout().move(HomeTileId.ADMIN, -1)
        assertEquals(HomeTileId.ADMIN, up.order[5])
    }

    @Test
    fun hideButNeverScore() {
        val l = HomeLayout().setHidden(HomeTileId.HANDICAP, true).setHidden(HomeTileId.SCORE, true)
        assertEquals(setOf(HomeTileId.HANDICAP), l.hidden)
        assertEquals(HomeLayout(), l.setHidden(HomeTileId.HANDICAP, false))
    }

    @Test
    fun storesAndReadsBack() {
        val l = HomeLayout().move(HomeTileId.FRIENDS, -3).setHidden(HomeTileId.LOCATIONS, true)
        assertEquals(l, HomeLayout.decode(l.encode()))
        assertEquals(HomeLayout(), HomeLayout.decode(null))
        assertEquals(HomeLayout(), HomeLayout.decode("rubbish"))
    }

    @Test
    fun newTilesGoInAtTheirUsualPlace() {
        // Saved by an older version without Handicap calculator (and an unknown name): it goes back after Stats.
        val l = HomeLayout.decode("SCORE,STATS,HISTORY,FRIENDS,LOCATIONS,ADMIN,OLD_TILE|SCORE")
        assertEquals(
            listOf(HomeTileId.SCORE, HomeTileId.STATS, HomeTileId.HANDICAP, HomeTileId.HISTORY, HomeTileId.FRIENDS, HomeTileId.LOCATIONS, HomeTileId.ADMIN),
            l.order,
        )
        assertTrue(l.hidden.isEmpty())
    }
}
