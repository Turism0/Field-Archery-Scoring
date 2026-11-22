package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Names you give other archers (3.2.23). The same cases as the web's archerNames.test.ts. */
class ArcherNamesTest {
    private val names = mapOf("a1" to "Dave Smith")

    @Test fun `your name is shown, else the username`() {
        assertEquals("Dave Smith", ArcherNames.display("archer_dave", "a1", names))
        assertEquals("sue99", ArcherNames.display("sue99", "a2", names))
        assertEquals("Robin", ArcherNames.display("Robin", null, names))   // a guest
    }

    @Test fun `the username goes underneath only when a name is shown instead`() {
        assertEquals("archer_dave", ArcherNames.usernameUnder("archer_dave", "a1", names))
        assertNull(ArcherNames.usernameUnder("sue99", "a2", names))
        assertNull(ArcherNames.usernameUnder("dave smith", "a1", names))   // same as the name
    }

    @Test fun `names are tidied, capped, and blank removes them`() {
        assertEquals("Dave  Smith".let { ArcherNames.clean(it) }, "Dave Smith")
        assertEquals(ArcherNames.MAX_LENGTH, ArcherNames.clean("x".repeat(60))!!.length)
        assertEquals(mapOf("a1" to "Dave Smith", "a2" to "Sue"), ArcherNames.withName(names, "a2", "  Sue "))
        assertEquals(emptyMap<String, String>(), ArcherNames.withName(names, "a1", "   "))
    }
}
