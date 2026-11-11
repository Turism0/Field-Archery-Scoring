// Whose rounds are on this device (3.2.02). The same cases as DeviceDataRulesTest.kt.
import { describe, expect, it } from 'vitest'
import { newRound, type Round } from '../src/domain/round'
import { ownRoundCount, signInData, signOutLoss, signOutWarning } from '../src/domain/deviceData'

const r = (id: string, p: Partial<Round> = {}): Round =>
  ({ ...newRound({ id, shootDate: '2026-11-01', targetCount: 36, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME' }), ...p })

describe('rounds on this device', () => {
  it('signing in keeps your own rounds, clears another account\'s and asks about signed-out ones', () => {
    expect(signInData('pete', 'pete', 90)).toBe('KEEP')
    expect(signInData('pete', 'newuser', 90)).toBe('CLEAR')
    expect(signInData(null, 'newuser', 3)).toBe('ASK')
    expect(signInData(null, 'newuser', 0)).toBe('KEEP')
    expect(signInData(undefined, 'newuser', 0)).toBe('KEEP')
  })

  it('counts only your own rounds', () => {
    expect(ownRoundCount([r('a'), r('b', { groupRole: 'SCORER' }), r('g', { groupRole: 'GUEST' }), r('f', { groupRole: 'FRIEND' }),
      r('m', { groupRole: 'MIRROR' }), r('d', { deleted: true })])).toBe(2)
  })

  it('signing out warns about unsent changes and rounds in progress', () => {
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
    expect(signOutWarning({ waiting: 1, inProgress: 0 })).toBe("1 change hasn't been backed up yet (it needs signal). Signing out now loses it.")
    expect(signOutWarning({ waiting: 3, inProgress: 1 })).toBe(
      "3 changes haven't been backed up yet (it needs signal), and 1 round is still in progress (rounds are backed up once finished). Signing out now loses them.")
  })
})
