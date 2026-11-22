// Names you give other archers (3.2.23, Pete): kept in this browser only, by their account id, and shown instead of
// their username in Friends, live groups and group scorecards, with the username in small text underneath. Never sent
// anywhere (the privacy policy's "no real name" stands). Same as the app's domain/ArcherNames.kt.
// Tested in test/archerNames.test.ts.

export const MAX_ARCHER_NAME = 40
export type ArcherNameMap = Record<string, string>

/** As stored: spaces tidied, at most [MAX_ARCHER_NAME] characters, null when blank (blank removes the name). */
export function cleanArcherName(name: string | null | undefined): string | null {
  const s = (name ?? '').trim().replace(/\s+/g, ' ').slice(0, MAX_ARCHER_NAME)
  return s || null
}

/** What to show for an archer: your name for them, else [fallback] (their username, or a guest's name). */
export const displayName = (fallback: string, archerId: string | null | undefined, names: ArcherNameMap) =>
  (archerId && names[archerId]) || fallback

/** The username to show underneath, when a name you gave is shown instead of it (null otherwise). */
export function usernameUnder(username: string | null | undefined, archerId: string | null | undefined, names: ArcherNameMap): string | null {
  const given = archerId ? names[archerId] : undefined
  if (!given || !username?.trim() || username.toLowerCase() === given.toLowerCase()) return null
  return username
}

/** [names] with [archerId]'s name set to [name], or removed when it's blank. */
export function withName(names: ArcherNameMap, archerId: string, name: string | null | undefined): ArcherNameMap {
  const clean = cleanArcherName(name)
  const { [archerId]: _, ...rest } = names
  return clean ? { ...rest, [archerId]: clean } : rest
}
