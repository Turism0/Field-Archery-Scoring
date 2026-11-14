package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.fnurble.fieldarchery.domain.DeviceDataRules.HiddenOwner
import uk.co.fnurble.fieldarchery.domain.DeviceDataRules.SignInPlan

/** Whose rounds are on this phone (3.2.02; 3.2.09 put away, not wiped). The same cases as the web app's deviceData.test.ts. */
class DeviceDataRulesTest {

    @Test
    fun signingInAsTheAccountWhoseRoundsAreShowingChangesNothing() {
        val p = DeviceDataRules.signInPlan("pete", "pete", "Turismo", 90, listOf(HiddenOwner("jo", "jo", 4)))
        assertEquals(SignInPlan(null, 0, emptyList(), 0), p)
        assertFalse(p.needsAsking)
    }

    @Test
    fun anotherAccountsShowingRoundsArePutAwayFirst() {
        assertEquals(SignInPlan("jo", 0, emptyList(), 0), DeviceDataRules.signInPlan("jo", "pete", "Turismo", 4, emptyList()))
    }

    @Test
    fun asksAboutRoundsScoredWhileNobodyWasSignedIn() {
        val p = DeviceDataRules.signInPlan(null, "pete", "Turismo", 3, emptyList())
        assertEquals(3, p.signedOut)
        assertTrue(p.needsAsking)
        assertEquals(
            "There are 3 rounds on this phone scored while nobody was signed in. Add them to Turismo's account (they're backed up again), or remove them from this phone?",
            DeviceDataRules.signInQuestion(p, "Turismo"),
        )
        assertEquals(0, DeviceDataRules.signInPlan(null, "pete", "Turismo", 0, emptyList()).signedOut)
    }

    @Test
    fun asksAboutAnEarlierAccountWithTheSameUsername() {
        val p = DeviceDataRules.signInPlan(null, "new-id", "turismo", 0, listOf(HiddenOwner("old-id", "Turismo", 95), HiddenOwner("jo", "jo", 4)))
        assertEquals(SignInPlan(null, 0, listOf("old-id"), 95), p)
        assertEquals(
            "There are 95 rounds on this phone from an earlier account called turismo (the server no longer has it). Add them to turismo's account (they're backed up again), or remove them from this phone?",
            DeviceDataRules.signInQuestion(p, "turismo"),
        )
        // Still showing (signed out by the server, then signed up again): put away, then asked about the same way.
        assertEquals(
            SignInPlan("old-id", 0, listOf("old-id"), 95),
            DeviceDataRules.signInPlan("old-id", "new-id", "Turismo", 95, listOf(HiddenOwner("old-id", "Turismo", 0))),
        )
    }

    @Test
    fun bothAtOnce() {
        val p = DeviceDataRules.signInPlan(null, "new", "pete", 1, listOf(HiddenOwner("old", "pete", 2)))
        assertEquals(
            "There are 3 rounds on this phone that aren't on this account: 1 round scored while nobody was signed in, and 2 rounds from an earlier account called pete (the server no longer has it). Add them to pete's account (they're backed up again), or remove them from this phone?",
            DeviceDataRules.signInQuestion(p, "pete"),
        )
    }

    @Test
    fun removingYourRoundsAtSignOutWarnsAboutUnsentChangesAndRoundsInProgress() {
        assertNull(DeviceDataRules.signOutWarning(0, 0))
        assertEquals("1 change hasn't been backed up yet (it needs signal). Removing your rounds now loses it.", DeviceDataRules.signOutWarning(1, 0))
        assertEquals(
            "3 changes haven't been backed up yet (it needs signal), and 1 round is still in progress (rounds are backed up once finished). " +
                "Removing your rounds now loses them.",
            DeviceDataRules.signOutWarning(3, 1),
        )
    }

    @Test
    fun findsRoundsTheServerIsMissing() {
        assertEquals(listOf("lost"), DeviceDataRules.missingOnServer(listOf("there", "lost"), setOf("there", "other")))
    }
}
