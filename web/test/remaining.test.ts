// Remaining and max possible while scoring (3.2.18). The same cases as the app's RemainingTest.kt.
import { describe, expect, it } from 'vitest'
import { newRound, roundRemaining } from '../src/domain/round'
import { newTarget, remainingPoints, withOverride, type AnimalResult, type TargetScore } from '../src/domain/scoring'

const S = (arrowNo: number, hit: 'INNER_KILL' | 'KILL' | 'WOUND'): AnimalResult => ({ state: 'SCORED', arrowNo, hit })
const P: AnimalResult = { state: 'PENDING' }
const M: AnimalResult = { state: 'MISSED' }
const t = (type: TargetScore['type'], animals: AnimalResult[], arrows = ''): TargetScore => ({ targetNo: 1, type, animals, overridePoints: null, arrows })

describe('remaining points', () => {
  it('Big Game targets', () => {
    expect(remainingPoints(newTarget('BIG_GAME', 1))).toBe(24)
    expect(remainingPoints(t('STANDARD', [S(2, 'KILL')]))).toBe(0)
    expect(remainingPoints(t('FAN', [S(1, 'KILL'), P, P]))).toBe(48)
    expect(remainingPoints(t('PREDATOR_PREY', [P, P]))).toBe(48)
    expect(remainingPoints(t('PREDATOR_PREY', [S(1, 'KILL'), P]))).toBe(24)
    expect(remainingPoints(t('PREDATOR_PREY', [S(3, 'WOUND'), P]))).toBe(0)
    expect(remainingPoints(t('PREDATOR_PREY', [M, P]))).toBe(0)
    expect(remainingPoints(withOverride(newTarget('BIG_GAME', 1), 10))).toBe(0)
    expect(remainingPoints(t('FUN', []))).toBe(0)
  })

  it('Woodsman: a kill on the next arrow', () => {
    expect(remainingPoints(newTarget('WOODSMAN', 1))).toBe(30)
    expect(remainingPoints(t('WOODSMAN', [], 'W'))).toBe(20)
    expect(remainingPoints(t('WOODSMAN', [], 'MW'))).toBe(10)
    expect(remainingPoints(t('WOODSMAN', [], 'WK'))).toBe(0)
    expect(remainingPoints(t('WOODSMAN', [], 'MMM'))).toBe(0)
  })

  it('a round: unscored targets count as one animal', () => {
    const r = newRound({ id: 'r', shootDate: '2026-11-18', targetCount: 4, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME' })
    const scored = { ...r, targets: [{ ...t('STANDARD', [S(1, 'KILL')]), targetNo: 1 }, { ...t('FAN', [S(2, 'WOUND'), P]), targetNo: 2 }] }
    // Total 30; remaining 24 (fan) + 24 + 24 (targets 3 and 4).
    expect(roundRemaining(scored)).toEqual({ remaining: 72, maxPossible: 102 })
  })
})
