package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Making a location private (3.2.12). The same cases as the web app's test/locationPrivacy.test.ts. */
class LocationPrivacyTest {
    @Test fun `doesn't ask when nobody else has shot there`() {
        assertNull(makePrivateWarning("Home", 0, 0))
    }

    @Test fun `warns about one round`() {
        assertEquals(
            "1 other archer has 1 round at Home. Making it private takes it off their scorecard, and it won't go back on if you make it public again.",
            makePrivateWarning("Home", 1, 1))
    }

    @Test fun `warns about several archers and rounds`() {
        assertEquals(
            "3 other archers have 7 rounds at Bowland Wood. Making it private takes it off their scorecards, and it won't go back on if you make it public again.",
            makePrivateWarning("Bowland Wood", 3, 7))
        assertEquals(
            "1 other archer has 4 rounds at Home. Making it private takes it off their scorecards, and it won't go back on if you make it public again.",
            makePrivateWarning("Home", 1, 4))
    }
}
