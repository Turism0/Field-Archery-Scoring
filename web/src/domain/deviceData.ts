// Whose rounds are on this device (3.2.02; 3.2.09, Pete: hidden, not wiped). Each account's rounds stay on the device:
// signing out puts them away (hidden), and signing in as that account brings them back. The same rules as the app's
// domain/DeviceDataRules.kt. Pure functions, tested in test/deviceData.test.ts.
import type { Round } from './round'

/** An account whose rounds are put away on this device. */
export interface HiddenOwner { archerId: string; username: string; rounds: number }

/** What signing in does with the rounds already on this device. */
export interface SignInPlan {
  /** The rounds showing belong to another account (its sign-in ended without signing out): put them away first. */
  hideFirst: string | null
  /** Rounds scored while nobody was signed in: asked about. */
  signedOut: number
  /**
   * Earlier accounts with this username (a different account id: deleted, or the server lost it) whose rounds are put
   * away here: asked about, so they can be added to this account (and sent to the server again).
   */
  earlier: string[]
  earlierRounds: number
}

/**
 * [visibleOwner] = the account the showing rounds belong to (null: nobody was signed in); [visibleRounds] = your own
 * rounds showing; [hidden] = accounts with rounds put away.
 */
export function signInPlan(visibleOwner: string | null | undefined, archerId: string, username: string,
  visibleRounds: number, hidden: HiddenOwner[]): SignInPlan {
  const hideFirst = visibleOwner && visibleOwner !== archerId ? visibleOwner : null
  // With the showing rounds put away first, they count as an earlier account's (below), not as signed-out rounds.
  const all = hideFirst ? [...hidden.filter(h => h.archerId !== hideFirst),
    { archerId: hideFirst, username: hidden.find(h => h.archerId === hideFirst)?.username ?? '', rounds: visibleRounds }] : hidden
  const earlier = all.filter(h => h.archerId !== archerId && h.rounds > 0 && h.username.toLowerCase() === username.toLowerCase())
  return {
    hideFirst,
    signedOut: visibleOwner ? 0 : visibleRounds,
    earlier: earlier.map(h => h.archerId),
    earlierRounds: earlier.reduce((n, h) => n + h.rounds, 0),
  }
}

export const needsAsking = (p: SignInPlan) => p.signedOut + p.earlierRounds > 0

/** The question, e.g. "There are 3 rounds on this device scored while nobody was signed in. Add them to pete's account…". */
export function signInQuestion(p: SignInPlan, username: string): string {
  const rounds = (n: number) => (n === 1 ? '1 round' : `${n} rounds`)
  const total = p.signedOut + p.earlierRounds, them = total === 1 ? 'it' : 'them'
  const what = p.signedOut && p.earlierRounds
    ? `${rounds(total)} on this device that aren't on this account: ${rounds(p.signedOut)} scored while nobody was signed in, and ${rounds(p.earlierRounds)} from an earlier account called ${username} (the server no longer has it)`
    : p.signedOut ? `${rounds(p.signedOut)} on this device scored while nobody was signed in`
    : `${rounds(p.earlierRounds)} on this device from an earlier account called ${username} (the server no longer has it)`
  return `There ${total === 1 ? 'is' : 'are'} ${what}. Add ${them} to ${username}'s account (${them === 'it' ? "it's" : "they're"} backed up again), or remove ${them} from this device?`
}

const own = (r: Round) => r.groupRole == null || r.groupRole === 'SCORER'

/** Your own rounds (not friends', guests' or copies of other phones' cards), in progress or finished. */
export const ownRoundCount = (rounds: Round[]) => rounds.filter(r => !r.deleted && own(r)).length

/** What removing your rounds from this device at sign-out would lose: changes not yet sent, and rounds in progress. */
export interface SignOutLoss { waiting: number; inProgress: number }

export function signOutLoss(rounds: Round[]): SignOutLoss {
  // The same rounds sync's upload sends: your own and friends' (requests), finished or deleted, still pending.
  const waiting = rounds.filter(r => r.pending && (own(r) || r.groupRole === 'FRIEND') && (r.deleted || r.status === 'COMPLETED')).length
  const inProgress = rounds.filter(r => !r.deleted && own(r) && r.status === 'IN_PROGRESS').length
  return { waiting, inProgress }
}

/** The warning under "Also remove my rounds from this device", or null when nothing would be lost. */
export function signOutWarning(l: SignOutLoss): string | null {
  const parts: string[] = []
  if (l.waiting) parts.push(`${l.waiting} change${l.waiting === 1 ? " hasn't" : "s haven't"} been backed up yet (it needs signal)`)
  if (l.inProgress) parts.push(`${l.inProgress} round${l.inProgress === 1 ? ' is' : 's are'} still in progress (rounds are backed up once finished)`)
  return parts.length ? `${parts.join(', and ')}. Removing your rounds now loses ${l.waiting + l.inProgress === 1 ? 'it' : 'them'}.` : null
}

/**
 * Rounds the server is missing (3.2.09): your finished rounds marked as backed up whose ids the server doesn't have
 * (e.g. it lost its data, or was restored from an older backup). They're sent again.
 */
export function missingOnServer(rounds: Round[], serverIds: Set<string>): string[] {
  return rounds.filter(r => own(r) && !r.deleted && r.status === 'COMPLETED' && !r.pending && !serverIds.has(r.id)).map(r => r.id)
}
