package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suggesting a known location instead of a new one, and finding them with no signal (3.2.25). The same cases as the web app's test/locationMatch.test.ts. */
class LocationMatchTest {
    @Test fun `keys ignore club words, case and punctuation`() {
        assertEquals("foobar", LocationMatch.key("The Bowmen of Foo-Bar!"))
        assertEquals("sherwood", LocationMatch.key("Sherwood Archers"))
        assertEquals("stmarys", LocationMatch.key("St. Mary's Field Archery Club"))
        assertEquals("archersclub", LocationMatch.key("Archers Club"))   // all common: keep them
    }

    @Test fun `similar names`() {
        assertTrue(LocationMatch.similar("sherwood", "sherwod"))
        assertTrue(LocationMatch.similar("sherwood", "sherwoodforest"))
        assertFalse(LocationMatch.similar("sherwood", "kielder"))
        assertFalse(LocationMatch.similar("abc", "abcd"))   // too short to count as containing
    }

    @Test fun `close names for suggestions (3_2_28)`() {
        assertTrue(LocationMatch.closeName("sherwood", "sherwod"))
        assertTrue(LocationMatch.closeName("sherwood", "sherwoodforest"))   // 8 of 14: at least half
        assertFalse(LocationMatch.closeName("test", "zzsimabcdefg"))         // "Test Ground" inside a longer name
        assertFalse(LocationMatch.closeName("kent", "kentishwoodsmen"))
        assertTrue(LocationMatch.similar("kent", "kentishwoodsmen"))         // the Admin rule is unchanged
    }

    private val club = KnownPlace("c", "Sherwood Archers", 53.2, -1.07)
    private val other = KnownPlace("o", "Kielder Bowmen", 55.2, -2.5)
    private val named = KnownPlace("n", "Nationals 2012", null, null)
    private val known = listOf(club, other, named)

    @Test fun `same name anywhere, similar name within 500 m or with no pin`() {
        assertEquals(listOf("c" to listOf("same_name")), LocationMatch.matches("Sherwood Bowmen", 51.0, 0.0, known).map { it.id to it.reasons })
        assertEquals(listOf("c"), LocationMatch.matches("Sherwod", null, null, known).map { it.id })   // no pin: distance unknown
        assertEquals(emptyList<PlaceMatch>(), LocationMatch.matches("Sherwod", 51.0, 0.0, known))      // far away
        val close = LocationMatch.matches("Sherwod", 53.2 + 0.004, -1.07, known)                       // ~445 m
        assertEquals(listOf("c" to listOf("similar_name")), close.map { it.id to it.reasons })
        assertEquals(listOf("n"), LocationMatch.matches("NATIONALS 2012", null, null, known).map { it.id })
    }

    @Test fun `any place within 300 m`() {
        val near = LocationMatch.matches("Robin's Wood", 53.2 + 0.002, -1.07, known)   // ~222 m
        assertEquals(listOf("c"), near.map { it.id })
        assertEquals(listOf("nearby"), near[0].reasons)
        assertTrue(near[0].distanceMetres!! in 200..240)
        assertEquals(emptyList<PlaceMatch>(), LocationMatch.matches("Robin's Wood", 53.2 + 0.004, -1.07, known))   // ~445 m
    }

    @Test fun `name and place together come first, and skipped ones are left out`() {
        val two = listOf(KnownPlace("x", "Robin Hood Field", 53.2 + 0.001, -1.07), club)
        assertEquals(listOf("c", "x"), LocationMatch.matches("Sherwood", 53.2 + 0.001, -1.07, two).map { it.id })
        assertEquals(listOf("x"), LocationMatch.matches("Sherwood", 53.2 + 0.001, -1.07, two, skip = setOf("c")).map { it.id })
    }

    @Test fun `nearby with no signal`() {
        assertEquals(listOf("c"), LocationMatch.nearby(known, 53.2 + 0.003, -1.07, 402).map { it.id })
        assertEquals(emptyList<NearbyLocation>(), LocationMatch.nearby(known, 53.2 + 0.01, -1.07, 402))
        assertEquals(listOf("c", "o"), LocationMatch.nearby(known, 54.0, -1.5, 300_000).map { it.id })
    }

    @Test fun `why`() {
        assertEquals("Same name · 222 m away", LocationMatch.why(PlaceMatch("c", "S", false, 222, listOf("same_name", "nearby"))))
        assertEquals("Similar name · 1.5 km away · your private location", LocationMatch.why(PlaceMatch("c", "S", true, 1500, listOf("similar_name"))))
        assertEquals("Same name · 236 km away", LocationMatch.why(PlaceMatch("c", "S", false, 236_400, listOf("same_name"))))
    }
}
