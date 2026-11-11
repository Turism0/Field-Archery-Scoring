package uk.co.fnurble.fieldarchery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.fnurble.fieldarchery.domain.DeviceDataRules.SignInData

/** Whose rounds are on this phone (3.2.02). The same cases as the web app's deviceData.test.ts. */
class DeviceDataRulesTest {

    @Test
    fun signingInKeepsYoursClearsAnotherAccountsAndAsksAboutSignedOutOnes() {
        assertEquals(SignInData.KEEP, DeviceDataRules.signInData("pete", "pete", 90))
        assertEquals(SignInData.CLEAR, DeviceDataRules.signInData("pete", "newuser", 90))
        assertEquals(SignInData.ASK, DeviceDataRules.signInData(null, "newuser", 3))
        assertEquals(SignInData.KEEP, DeviceDataRules.signInData(null, "newuser", 0))
    }

    @Test
    fun signingOutWarnsAboutUnsentChangesAndRoundsInProgress() {
        assertNull(DeviceDataRules.signOutWarning(0, 0))
        assertEquals("1 change hasn't been backed up yet (it needs signal). Signing out now loses it.", DeviceDataRules.signOutWarning(1, 0))
        assertEquals(
            "3 changes haven't been backed up yet (it needs signal), and 1 round is still in progress (rounds are backed up once finished). " +
                "Signing out now loses them.",
            DeviceDataRules.signOutWarning(3, 1),
        )
    }
}
