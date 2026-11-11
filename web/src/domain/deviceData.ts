// Whose rounds are on this device (3.2.02, Pete): signing out clears the device, and signing in decides what happens
// to rounds already here. The same rules as the app's domain/DeviceDataRules.kt. Pure functions, tested in
// test/deviceData.test.ts.
import type { Round } from './round'

/**
 * What signing in does with the rounds already on this device.
 * KEEP: they're this account's (or there are none). CLEAR: another account's, left by a sign-in that ended without
 * signing out (the server ended it); removed without asking. ASK: scored while nobody was signed in; the archer chooses.
 */
export type SignInData = 'KEEP' | 'CLEAR' | 'ASK'

/** [owner] = the account the device's rounds belong to (null when nobody was signed in); [localRounds] = your own rounds here. */
export function signInData(owner: string | null | undefined, archerId: string, localRounds: number): SignInData {
  if (owner) return owner === archerId ? 'KEEP' : 'CLEAR'
  return localRounds > 0 ? 'ASK' : 'KEEP'
}

const own = (r: Round) => r.groupRole == null || r.groupRole === 'SCORER'

/** Your own rounds on this device (not friends', guests' or copies of other phones' cards), in progress or finished. */
export const ownRoundCount = (rounds: Round[]) => rounds.filter(r => !r.deleted && own(r)).length

/** What signing out would lose: changes not yet sent to the server, and rounds still in progress (never sent until finished). */
export interface SignOutLoss { waiting: number; inProgress: number }

export function signOutLoss(rounds: Round[]): SignOutLoss {
  // The same rounds sync's upload sends: your own and friends' (requests), finished or deleted, still pending.
  const waiting = rounds.filter(r => r.pending && (own(r) || r.groupRole === 'FRIEND') && (r.deleted || r.status === 'COMPLETED')).length
  const inProgress = rounds.filter(r => !r.deleted && own(r) && r.status === 'IN_PROGRESS').length
  return { waiting, inProgress }
}

/** The sign-out dialog's warning, or null when nothing would be lost. */
export function signOutWarning(l: SignOutLoss): string | null {
  const parts: string[] = []
  if (l.waiting) parts.push(`${l.waiting} change${l.waiting === 1 ? " hasn't" : "s haven't"} been backed up yet (it needs signal)`)
  if (l.inProgress) parts.push(`${l.inProgress} round${l.inProgress === 1 ? ' is' : 's are'} still in progress (rounds are backed up once finished)`)
  return parts.length ? `${parts.join(', and ')}. Signing out now loses ${l.waiting + l.inProgress === 1 ? 'it' : 'them'}.` : null
}
