package uk.co.fnurble.nfas.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesTest {
    @Test
    fun usernames() {
        assertTrue(AccountRules.isValidUsername("pete"))
        assertTrue(AccountRules.isValidUsername("Pete_B.archer-1"))
        assertTrue(AccountRules.isValidUsername(" pete "))           // trimmed
        assertFalse(AccountRules.isValidUsername("pb"))              // too short
        assertFalse(AccountRules.isValidUsername("a".repeat(31)))    // too long
        assertFalse(AccountRules.isValidUsername("pete bullock"))    // space
        assertFalse(AccountRules.isValidUsername("pété"))            // accents
        assertFalse(AccountRules.isValidUsername("pete!"))
    }

    @Test
    fun newPasswords() {
        assertNull(AccountRules.newPasswordProblem("longenough", "longenough"))
        assertEquals("The two passwords don't match.", AccountRules.newPasswordProblem("longenough", "longenougH"))
        assertTrue(AccountRules.newPasswordProblem("short", "short")!!.contains("at least"))
        assertTrue(AccountRules.newPasswordProblem("x".repeat(129), "x".repeat(129))!!.contains("at most"))
    }
}
