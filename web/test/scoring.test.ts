// The Android app's ScoringTest cases, for the web app's copy of the rules (2.5).
import { describe, expect, it } from 'vitest'
import {
  bigGamePoints, newTarget, withAnimalResult, withType, withOverride, totalPoints, maxPoints, isComplete, arrowsAvailable,
  woodsmanPoints, woodsmanValid, woodsmanFinished, withArrow, matchKey, type AnimalResult, type TargetScore,
} from '../src/domain/scoring'
import { newRound, roundStats, canGoTo, firstUnscored, roundToJson, roundFromJson, type Round } from '../src/domain/round'

const S = (arrowNo: number, hit: 'INNER_KILL' | 'KILL' | 'WOUND'): AnimalResult => ({ state: 'SCORED', arrowNo, hit })
const MISS: AnimalResult = { state: 'MISSED' }
const t1 = () => newTarget('BIG_GAME', 1)

function roundOf(...targets: TargetScore[]): Round {
  const r = newRound({ shootDate: '2026-11-09', targetCount: targets.length, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME' })
  return { ...r, targets: targets.map((t, i) => ({ ...t, targetNo: i + 1 })) }
}

describe('Big Game', () => {
  it('scores like the app', () => {
    expect([bigGamePoints(1, 'INNER_KILL'), bigGamePoints(1, 'KILL'), bigGamePoints(1, 'WOUND')]).toEqual([24, 20, 16])
    expect([bigGamePoints(2, 'KILL'), bigGamePoints(2, 'WOUND'), bigGamePoints(3, 'KILL'), bigGamePoints(3, 'WOUND')]).toEqual([14, 10, 8, 4])
    expect(() => bigGamePoints(2, 'INNER_KILL')).toThrow()
  })
  it('predator / prey max is 48', () => {
    const t = withAnimalResult(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(1, 'INNER_KILL')), 1, S(1, 'INNER_KILL'))
    expect(totalPoints(t)).toBe(48); expect(maxPoints(t)).toBe(48); expect(isComplete(t)).toBe(true)
  })
  it('prey gets the remaining arrows, scored per animal', () => {
    let t = withType(t1(), 'PREDATOR_PREY')
    expect(arrowsAvailable(t.type, t.animals, 1)).toBe(0)
    t = withAnimalResult(t, 0, S(1, 'KILL'))
    expect(arrowsAvailable(t.type, t.animals, 1)).toBe(2)
    t = withAnimalResult(t, 1, S(2, 'KILL'))
    expect(totalPoints(t)).toBe(34)
    t = withAnimalResult(t, 0, S(2, 'WOUND'))   // prey only had 1 now: reset
    expect(t.animals[1].state).toBe('PENDING')
    t = withAnimalResult(t, 1, S(1, 'WOUND'))
    expect(totalPoints(t)).toBe(26)
  })
  it('prey limits and completion', () => {
    expect(() => withAnimalResult(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(2, 'KILL')), 1, S(2, 'KILL'))).toThrow()
    expect(() => withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 1, S(1, 'KILL'))).toThrow()
    expect(isComplete(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, MISS))).toBe(true)
    expect(isComplete(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(3, 'KILL')))).toBe(true)
    expect(isComplete(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(1, 'KILL')))).toBe(false)
  })
  it('fans', () => {
    let t = withType(t1(), 'FAN', 3)
    expect(maxPoints(t)).toBe(72)
    t = withAnimalResult(withAnimalResult(withAnimalResult(t, 0, S(1, 'INNER_KILL')), 1, S(3, 'KILL')), 2, MISS)
    expect(totalPoints(t)).toBe(32); expect(isComplete(t)).toBe(true)
    expect(() => withType(t1(), 'FAN', 1)).toThrow(); expect(() => withType(t1(), 'FAN', 7)).toThrow()
  })
  it('override replaces the calculated score', () => {
    const t = withOverride(withAnimalResult(t1(), 0, S(1, 'KILL')), 15)
    expect(totalPoints(t)).toBe(15); expect(isComplete(withOverride(t1(), 0))).toBe(true)
  })
  it('no skipping ahead', () => {
    const r = roundOf(withAnimalResult(t1(), 0, S(1, 'KILL')), t1(), t1())
    expect(canGoTo(r, 2)).toBe(true); expect(canGoTo(r, 3)).toBe(false); expect(firstUnscored(r)).toBe(2)
  })
  it('average per animal counts predator/prey and fan animals', () => {
    const r = roundOf(
      withAnimalResult(t1(), 0, S(1, 'KILL')),
      withAnimalResult(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(1, 'KILL')), 1, S(2, 'WOUND')),
      [S(1, 'INNER_KILL'), S(1, 'KILL'), MISS, MISS].reduce((t, a, i) => withAnimalResult(t, i, a), withType(t1(), 'FAN', 4)),
    )
    const s = roundStats(r)
    expect(s.total).toBe(94); expect(s.averagePerAnimal).toBeCloseTo(94 / 7)
    expect(roundStats(roundOf(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(3, 'KILL')))).averagePerAnimal).toBeCloseTo(4)
  })
})

describe('Woodsman', () => {
  it('scores like the app', () => {
    expect(woodsmanPoints('K')).toBe(30); expect(woodsmanPoints('WK')).toBe(25); expect(woodsmanPoints('MWK')).toBe(15)
    expect(woodsmanPoints('WWW')).toBe(15); expect(woodsmanPoints('MMM')).toBe(0); expect(woodsmanPoints('MK')).toBe(20)
    expect(woodsmanValid('KW')).toBe(false); expect(woodsmanValid('WWWW')).toBe(false); expect(woodsmanFinished('WK')).toBe(true)
    let t = newTarget('WOODSMAN', 1)
    t = withArrow(withArrow(withArrow(t, 'K'), 'W'), 'W')   // nothing after a kill
    expect(t.arrows).toBe('K')
  })
})

describe('JSON and matching', () => {
  it('a round goes to the server shape and back', () => {
    const r = roundOf(withAnimalResult(withType(t1(), 'PREDATOR_PREY'), 0, S(1, 'KILL')), withAnimalResult(t1(), 0, MISS))
    const j: any = roundToJson(r)
    expect(j.archerClass).toBe('G'); expect(j.totalPoints).toBe(20); expect(j.targets[0].animals[0]).toEqual({ index: 0, state: 'SCORED', arrowNo: 1, hit: 'KILL', points: 20 })
    const back = roundFromJson({ ...j, roundId: r.id })
    expect(back.targets).toEqual(r.targets)
  })
  it('same points but a different hit don\'t match', () => {
    expect(matchKey(withAnimalResult(t1(), 0, S(2, 'KILL')))).not.toBe(matchKey(withAnimalResult(t1(), 0, S(1, 'WOUND'))))
  })
})
