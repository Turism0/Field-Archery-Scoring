// Whose rounds are on this device (3.2.02; 3.2.09 hidden, not wiped). The same cases as DeviceDataRulesTest.kt.
import { describe, expect, it } from 'vitest'
import { newRound, type Round } from '../src/domain/round'
import { missingOnServer, needsAsking, ownRoundCount, signInPlan, signInQuestion, signOutLoss, signOutWarning } from '../src/domain/deviceData'

const r = (id: string, p: Partial<Round> = {}): Round =>
  ({ ...newRound({ id, shootDate: '2026-11-01', targetCount: 36, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME' }), ...p })

describe('rounds on this device', () => {
  it('signing in as the account whose rounds are showing changes nothing', () => {
    const p = signInPlan('pete', 'pete', 'Turismo', 90, [{ archerId: 'jo', username: 'jo', rounds: 4 }])
    expect(p).toEqual({ hideFirst: null, signedOut: 0, earlier: [], earlierRounds: 0 })
    expect(needsAsking(p)).toBe(false)
  })

  it("another account's showing rounds are put away first (its sign-in ended without signing out)", () => {
    expect(signInPlan('jo', 'pete', 'Turismo', 4, [])).toEqual({ hideFirst: 'jo', signedOut: 0, earlier: [], earlierRounds: 0 })
  })

  it('asks about rounds scored while nobody was signed in', () => {
    const p = signInPlan(null, 'pete', 'Turismo', 3, [])
    expect(p.signedOut).toBe(3); expect(needsAsking(p)).toBe(true)
    expect(signInQuestion(p, 'Turismo')).toBe(
      "There are 3 rounds on this device scored while nobody was signed in. Add them to Turismo's account (they're backed up again), or remove them from this device?")
    expect(signInPlan(null, 'pete', 'Turismo', 0, []).signedOut).toBe(0)
  })

  it('asks about an earlier account with the same username (the server lost it, or it was deleted)', () => {
    const p = signInPlan(null, 'new-id', 'turismo', 0, [{ archerId: 'old-id', username: 'Turismo', rounds: 95 }, { archerId: 'jo', username: 'jo', rounds: 4 }])
    expect(p).toEqual({ hideFirst: null, signedOut: 0, earlier: ['old-id'], earlierRounds: 95 })
    expect(signInQuestion(p, 'turismo')).toBe(
      "There are 95 rounds on this device from an earlier account called turismo (the server no longer has it). Add them to turismo's account (they're backed up again), or remove them from this device?")
    // Still showing (signed out by the server, then signed up again): put away, then asked about the same way.
    expect(signInPlan('old-id', 'new-id', 'Turismo', 95, [{ archerId: 'old-id', username: 'Turismo', rounds: 0 }]))
      .toEqual({ hideFirst: 'old-id', signedOut: 0, earlier: ['old-id'], earlierRounds: 95 })
  })

  it('both at once', () => {
    const p = signInPlan(null, 'new', 'pete', 1, [{ archerId: 'old', username: 'pete', rounds: 2 }])
    expect(signInQuestion(p, 'pete')).toBe(
      "There are 3 rounds on this device that aren't on this account: 1 round scored while nobody was signed in, and 2 rounds from an earlier account called pete (the server no longer has it). Add them to pete's account (they're backed up again), or remove them from this device?")
  })

  it('counts only your own rounds', () => {
    expect(ownRoundCount([r('a'), r('b', { groupRole: 'SCORER' }), r('g', { groupRole: 'GUEST' }), r('f', { groupRole: 'FRIEND' }),
      r('m', { groupRole: 'MIRROR' }), r('d', { deleted: true })])).toBe(2)
  })

  it('removing your rounds at sign-out warns about unsent changes and rounds in progress', () => {
    const rounds = [
      r('sent', { status: 'COMPLETED', pending: false }),
      r('waiting', { status: 'COMPLETED', pending: true }),
      r('deleted', { deleted: true, pending: true }),
      r('friend', { groupRole: 'FRIEND', status: 'COMPLETED', pending: true }),
      r('guest', { groupRole: 'GUEST', status: 'COMPLETED', pending: true }),
      r('scoring', { status: 'IN_PROGRESS', pending: true }),
    ]
    expect(signOutLoss(rounds)).toEqual({ waiting: 3, inProgress: 1 })
    expect(signOutWarning({ waiting: 0, inProgress: 0 })).toBeNull()
    expect(signOutWarning({ waiting: 1, inProgress: 0 })).toBe("1 change hasn't been backed up yet (it needs signal). Removing your rounds now loses it.")
    expect(signOutWarning({ waiting: 3, inProgress: 1 })).toBe(
      "3 changes haven't been backed up yet (it needs signal), and 1 round is still in progress (rounds are backed up once finished). Removing your rounds now loses them.")
  })

  it('finds finished rounds the server is missing', () => {
    const rounds = [
      r('there', { status: 'COMPLETED', pending: false }),
      r('lost', { status: 'COMPLETED', pending: false }),
      r('waiting', { status: 'COMPLETED', pending: true }),
      r('scoring', { status: 'IN_PROGRESS', pending: false }),
      r('gone', { status: 'COMPLETED', pending: false, deleted: true }),
      r('guest', { status: 'COMPLETED', pending: false, groupRole: 'GUEST' }),
    ]
    expect(missingOnServer(rounds, new Set(['there']))).toEqual(['lost'])
  })
})
