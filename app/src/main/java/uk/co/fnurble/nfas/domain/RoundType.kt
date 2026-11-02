package uk.co.fnurble.nfas.domain

/**
 * Types of round the app can score, offered when starting a new round. Every round records its type
 * (phone database v3, server schema v5). To add a type: add an entry here, its scoring rules (Scoring.kt),
 * its scoring panel and the server's rules (RoundValidator.cs).
 */
enum class RoundType(val displayName: String, val description: String) {
    BIG_GAME(
        displayName = "NFAS Big Game",
        description = "3D animal targets, up to 3 arrows each: inner kill, kill or wound. " +
            "Includes predator/prey and fan targets.",
    ),
    WOODSMAN(
        displayName = "Woodsman",
        description = "One animal per target: shoot until you kill it, up to 3 arrows. " +
            "Wound 5 on any arrow; kill 30, 20 or 10 on arrow 1, 2 or 3; miss 0.",
    ),
}
