// The Stats screen's filters (3.2): round types, bow styles and a date range. The same cases as StatsFilterTest.kt.
import { describe, expect, it } from 'vitest'
import { newRound, type Round } from '../src/domain/round'
import { inStatsPeriod, matchesFilter, periodLabel, resolveFilter, toggled, typeOrder } from '../src/domain/statsFilter'
import type { RoundType } from '../src/domain/scoring'

const r = (id: string, date: string, bow: string, type: RoundType = 'BIG_GAME'): Round =>
  ({ ...newRound({ id, shootDate: date, targetCount: 36, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: bow, type }), status: 'COMPLETED' })
const TODAY = '2026-11-12'

describe('stats filter', () => {
  it('rolling years run back from today', () => {
    expect(inStatsPeriod({ kind: 'ROLLING', years: 1 }, '2025-11-13', TODAY)).toBe(true)
    expect(inStatsPeriod({ kind: 'ROLLING', years: 1 }, '2025-11-12', TODAY)).toBe(false)
    expect(inStatsPeriod({ kind: 'ROLLING', years: 3 }, '2023-12-01', TODAY)).toBe(true)
  })
  it('calendar years include this year', () => {
    expect(inStatsPeriod({ kind: 'CALENDAR', years: 1 }, '2026-01-01', TODAY)).toBe(true)
    expect(inStatsPeriod({ kind: 'CALENDAR', years: 1 }, '2025-12-31', TODAY)).toBe(false)
    expect(inStatsPeriod({ kind: 'CALENDAR', years: 2 }, '2025-01-01', TODAY)).toBe(true)
    expect(inStatsPeriod({ kind: 'ALL', years: 0 }, '2001-01-01', TODAY)).toBe(true)
  })
  it('labels', () => {
    expect(periodLabel({ kind: 'ROLLING', years: 1 }, TODAY)).toBe('Last 1 year (rolling)')
    expect(periodLabel({ kind: 'CALENDAR', years: 1 }, TODAY)).toBe('This year (2026)')
    expect(periodLabel({ kind: 'CALENDAR', years: 3 }, TODAY)).toBe('Last 3 years (2024–2026)')
    expect(periodLabel({ kind: 'ALL', years: 0 }, TODAY)).toBe('All time')
  })
  it('first time: every type, the profile bow, the last year rolling', () => {
    const rounds = [r('a', '2026-01-01', 'CL'), r('b', '2026-02-01', 'CL'), r('c', '2015-04-12', 'AFB'), r('w', '2026-03-01', 'LB', 'WOODSMAN')]
    expect(resolveFilter(null, rounds, 'AFB')).toEqual({ types: ['BIG_GAME', 'WOODSMAN'], bows: ['AFB'], period: { kind: 'ROLLING', years: 1 } })
    // A profile bow with no rounds (or none set): the bow shot most.
    expect(resolveFilter(null, rounds, 'BB').bows).toEqual(['CL'])
    expect(resolveFilter(null, rounds, null).bows).toEqual(['CL'])
  })
  it('remembers the last choice, less anything with no rounds', () => {
    const rounds = [r('a', '2026-01-01', 'CL'), r('c', '2015-04-12', 'AFB')]
    const saved = { types: ['WOODSMAN', 'BIG_GAME'] as RoundType[], bows: ['AFB', 'LB'], period: { kind: 'CALENDAR' as const, years: 5 } }
    expect(resolveFilter(saved, rounds, 'CL')).toEqual({ types: ['BIG_GAME'], bows: ['AFB'], period: { kind: 'CALENDAR', years: 5 } })
    // Nothing left of a saved choice: back to the defaults for that part.
    expect(resolveFilter({ bows: ['LB'] }, rounds, 'CL').bows).toEqual(['CL'])
  })
  it('matches type, bow and dates', () => {
    const f = { types: ['BIG_GAME'] as RoundType[], bows: ['CL', 'AFB'], period: { kind: 'ROLLING' as const, years: 1 } }
    expect(matchesFilter(f, r('a', '2026-06-01', 'CL'), TODAY)).toBe(true)
    expect(matchesFilter(f, r('b', '2026-06-01', 'LB'), TODAY)).toBe(false)
    expect(matchesFilter(f, r('c', '2024-06-01', 'CL'), TODAY)).toBe(false)
    expect(matchesFilter(f, r('w', '2026-06-01', 'CL', 'WOODSMAN'), TODAY)).toBe(false)
  })
  it('toggling never leaves a choice empty', () => {
    expect(toggled<RoundType>(['BIG_GAME'], 'BIG_GAME', typeOrder)).toEqual(['BIG_GAME'])
    expect(toggled<RoundType>(['WOODSMAN'], 'BIG_GAME', typeOrder)).toEqual(['BIG_GAME', 'WOODSMAN'])
    expect(toggled<RoundType>(['BIG_GAME', 'WOODSMAN'], 'BIG_GAME', typeOrder)).toEqual(['WOODSMAN'])
  })
})
