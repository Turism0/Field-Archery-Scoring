// Round notes (3.2.21, Pete): searching Score history by them. Same as the app's domain/RoundNotes.kt.
// Tested in test/roundNotes.test.ts.

/**
 * True when every word of [query] appears somewhere in [notes] (any case, any order). A blank query matches every
 * round, with or without notes; any other query only matches rounds that have notes.
 */
export function notesMatch(notes: string | null | undefined, query: string): boolean {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return true
  if (!notes) return false
  const text = notes.toLowerCase()
  return words.every(w => text.includes(w))
}
