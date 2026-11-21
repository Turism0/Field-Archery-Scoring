package uk.co.fieldarcheryscoring.domain

/** Round notes (3.2.21, Pete): searching Score history by them. Same as the web's domain/roundNotes.ts. */
object RoundNotes {
    /**
     * True when every word of [query] appears somewhere in [notes] (any case, any order). A blank query matches every
     * round, with or without notes; any other query only matches rounds that have notes.
     */
    fun matches(notes: String?, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = notes?.lowercase() ?: return false
        return words.all { it in text }
    }
}
