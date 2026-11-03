package uk.co.fnurble.nfas.domain

import java.time.LocalDate
import java.time.Period

/** Archer class. */
enum class ArcherClass(val displayName: String) {
    LADIES("Ladies"),
    GENTS("Gents"),
}

/**
 * Age groups. Age is taken on the day of the shoot.
 * Adult 16+, Junior 12–15, Cub under 12.
 * (Juniors and Cubs shoot from different pegs within their group; the round only records the group.)
 */
enum class AgeGroup(val displayName: String) {
    ADULT("Adult"),
    JUNIOR("Junior"),
    CUB("Cub");

    companion object {
        fun forAge(age: Int): AgeGroup {
            require(age >= 0) { "Age cannot be negative" }
            return when {
                age >= 16 -> ADULT
                age >= 12 -> JUNIOR
                else -> CUB
            }
        }

        fun ageOn(dateOfBirth: LocalDate, onDate: LocalDate): Int {
            require(!onDate.isBefore(dateOfBirth)) { "Date is before date of birth" }
            return Period.between(dateOfBirth, onDate).years
        }

        fun forDateOfBirth(dateOfBirth: LocalDate, shootDate: LocalDate): AgeGroup =
            forAge(ageOn(dateOfBirth, shootDate))
    }
}

/** Bow styles with their short codes. */
enum class BowStyle(val code: String, val displayName: String) {
    AMERICAN_FLATBOW("AFB", "American Flatbow"),
    BAREBOW("BB", "Barebow"),
    BOWHUNTER("BH", "Bowhunter"),
    COMPOUND_LIMITED("CL", "Compound Limited"),
    CROSSBOW("XB", "Crossbow"),
    TRADITIONAL_CROSSBOW("TXB", "Traditional Crossbow"),
    FREESTYLE("FS", "Freestyle"),
    HUNTING_TACKLE("HT", "Hunting Tackle"),
    LONGBOW("LB", "Longbow"),
    PRIMITIVE("PV", "Primitive"),
    THUMB_DRAW("TD", "Thumb Draw"),
    TRADITIONAL_BOWHUNTER("TB", "Traditional Bowhunter"),
    UNLIMITED("UL", "Unlimited");

    val label: String get() = "$displayName ($code)"

    companion object {
        fun fromCode(code: String): BowStyle? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Works out the age group for a round: a manual choice wins, otherwise it comes from the
 * date of birth on the shoot date. Null if neither is available.
 */
fun resolveAgeGroup(manual: AgeGroup?, dateOfBirth: LocalDate?, shootDate: LocalDate): AgeGroup? =
    manual ?: dateOfBirth
        ?.takeIf { !shootDate.isBefore(it) }
        ?.let { AgeGroup.forDateOfBirth(it, shootDate) }
