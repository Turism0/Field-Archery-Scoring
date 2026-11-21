package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Searching history by round notes (3.2.21). The same cases as the web's roundNotes.test.ts. */
class RoundNotesTest {
    private val notes = "New arrows, windy.\nStruggled on the uphill shots"

    @Test fun `blank search matches everything`() {
        assertTrue(RoundNotes.matches(null, "")); assertTrue(RoundNotes.matches(notes, "   "))
    }

    @Test fun `every word must appear, any case and order`() {
        assertTrue(RoundNotes.matches(notes, "windy"))
        assertTrue(RoundNotes.matches(notes, "UPHILL  arrows"))
        assertTrue(RoundNotes.matches(notes, "up"))
        assertFalse(RoundNotes.matches(notes, "windy rain"))
    }

    @Test fun `a search never matches a round without notes`() {
        assertFalse(RoundNotes.matches(null, "windy"))
    }
}
