package uk.co.fnurble.nfas.domain

/**
 * Types of round the app can score, offered when starting a new round.
 * To add a type: add an entry here, then give it its own scoring rules and screen.
 * (When there's more than one, rounds will also record their type: a database change.)
 */
enum class RoundType(val displayName: String, val description: String) {
    BIG_GAME(
        displayName = "NFAS Big Game",
        description = "3D animal targets, up to 3 arrows each: inner kill, kill or wound. " +
            "Includes predator/prey and fan targets.",
    ),
}
