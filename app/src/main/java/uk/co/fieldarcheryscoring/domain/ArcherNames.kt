package uk.co.fieldarcheryscoring.domain

/**
 * Names you give other archers (3.2.23, Pete): kept on this phone only, by their account id, and shown instead of their
 * username in Friends, live groups, group scorecards and messages, with the username in small text underneath. Never
 * sent anywhere (the privacy policy's "no real name" stands). Same as the web's domain/archerNames.ts.
 */
object ArcherNames {
    const val MAX_LENGTH = 40

    /** As stored: spaces tidied, at most [MAX_LENGTH] characters, null when blank (blank removes the name). */
    fun clean(name: String?): String? = name?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_LENGTH)?.takeIf { it.isNotEmpty() }

    /** What to show for an archer: your name for them, else [fallback] (their username, or a guest's name). */
    fun display(fallback: String, archerId: String?, names: Map<String, String>): String =
        archerId?.let { names[it] } ?: fallback

    /** The username to show underneath, when a name you gave is shown instead of it (null otherwise). */
    fun usernameUnder(username: String?, archerId: String?, names: Map<String, String>): String? {
        val given = archerId?.let { names[it] } ?: return null
        return username?.takeIf { it.isNotBlank() && !it.equals(given, ignoreCase = true) }
    }

    /** [names] with [archerId]'s name set to [name], or removed when it's blank. */
    fun withName(names: Map<String, String>, archerId: String, name: String?): Map<String, String> =
        clean(name)?.let { names + (archerId to it) } ?: (names - archerId)
}
