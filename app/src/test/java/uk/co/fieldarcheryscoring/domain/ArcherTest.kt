package uk.co.fieldarcheryscoring.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ArcherTest {

    @Test
    fun ageGroupBoundaries() {
        assertEquals(AgeGroup.CUB, AgeGroup.forAge(8))
        assertEquals(AgeGroup.CUB, AgeGroup.forAge(11))
        assertEquals(AgeGroup.JUNIOR, AgeGroup.forAge(12))
        assertEquals(AgeGroup.JUNIOR, AgeGroup.forAge(15))
        assertEquals(AgeGroup.ADULT, AgeGroup.forAge(16))
        assertEquals(AgeGroup.ADULT, AgeGroup.forAge(70))
    }

    @Test
    fun ageTakenOnShootDayMovesUpOnBirthday() {
        val dob = LocalDate.of(2010, 6, 15)
        assertEquals(AgeGroup.JUNIOR, AgeGroup.forDateOfBirth(dob, LocalDate.of(2026, 6, 14)))
        assertEquals(AgeGroup.ADULT, AgeGroup.forDateOfBirth(dob, LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun manualAgeGroupWins() {
        val dob = LocalDate.of(2015, 1, 1)
        val shoot = LocalDate.of(2026, 10, 30)
        assertEquals(AgeGroup.CUB, resolveAgeGroup(null, dob, shoot))
        assertEquals(AgeGroup.JUNIOR, resolveAgeGroup(AgeGroup.JUNIOR, dob, shoot))
        assertNull(resolveAgeGroup(null, null, shoot))
    }

    @Test
    fun bowStyles() {
        assertEquals(13, BowStyle.entries.size)
        assertEquals(BowStyle.TRADITIONAL_CROSSBOW, BowStyle.fromCode("TXB"))
        assertEquals(13, BowStyle.entries.map { it.code }.toSet().size)
    }
}
