// Making a location private or public from its page (3.2.12, Pete): only the archer who named it. Making it private when
// other archers have shot there takes it off their scorecards, so the app warns and asks first. The same text as the
// app's domain/LocationPrivacy.kt. Tested in test/locationPrivacy.test.ts.

const count = (n: number, what: string) => (n === 1 ? `1 ${what}` : `${n} ${what}s`)

/** The warning before making a location private, or null when nobody else has shot there (no need to ask). */
export function makePrivateWarning(name: string, otherArchers: number, otherRounds: number): string | null {
  if (otherRounds <= 0) return null
  const archers = Math.max(1, otherArchers)
  return `${count(archers, 'other archer')} ${archers === 1 ? 'has' : 'have'} ${count(otherRounds, 'round')} at ${name}. ` +
    `Making it private takes it off their ${otherRounds === 1 ? 'scorecard' : 'scorecards'}, ` +
    "and it won't go back on if you make it public again."
}

/**
 * The warning before deleting a location (3.2.16, Pete: the archer who named it, or the Owner). Rounds there keep
 * everything but the location, so archers can give them a new one.
 */
export function deleteLocationWarning(name: string, roundsHere: number): string {
  const what = roundsHere <= 0 ? ''
    : roundsHere === 1 ? ' The 1 round there loses its location; its archer can give it a new one.'
    : ` The ${roundsHere} rounds there (anyone's) lose their location; each archer can give theirs a new one.`
  return `Delete ${name}?${what} This can't be undone.`
}
