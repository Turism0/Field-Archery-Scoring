// The Android app's PersonalStatsTest, HandicapTest and HomeLayoutTest cases, for the web app's copies (2.6).
import { describe, expect, it } from 'vitest'
import { newTarget, withOverride, type RoundType } from '../src/domain/scoring'
import { newRound, type Round } from '../src/domain/round'
import { bestAverages, bests, inPeriod, personalBestIds, previousBest, seasons, trend, years } from '../src/domain/stats'
import { convert, handicapPoints, neutralOffset, offset, rating, REFERENCE, type HArcher, type HandicapModel } from '../src/domain/handicap'
import { DEFAULT_LAYOUT, TILES, isDefault, move, normalised, setHidden } from '../src/domain/homeLayout'

function round(id: string, date: string, perTarget: number, o: { targets?: number; bow?: string; created?: string; status?: 'COMPLETED' | 'IN_PROGRESS'; scored?: number; type?: RoundType; location?: string } = {}): Round {
  const targets = o.targets ?? 36, type = o.type ?? 'BIG_GAME'
  const r = newRound({ id, shootDate: date, targetCount: targets, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: o.bow ?? 'LB', type })
  return {
    ...r, status: o.status ?? 'COMPLETED', createdUtc: o.created ?? '2026-01-01T00:00:00.000Z', locationName: o.location ?? null,
    targets: Array.from({ length: o.scored ?? targets }, (_, i) => withOverride(newTarget(type, i + 1), perTarget)),
  }
}

describe('personal stats', () => {
  it('best total per type, bow and targets', () => {
    const rs = [
      round('a', '2026-03-01', 15), round('b', '2026-04-01', 18), round('c', '2026-05-01', 20, { targets: 40 }),
      round('d', '2026-06-01', 22, { bow: 'BB' }), round('e', '2026-07-01', 23, { scored: 30 }), round('f', '2026-08-01', 24, { status: 'IN_PROGRESS' }),
    ]
    const b = bests(rs)
    const lb36 = b.find(x => x.bowStyle === 'LB' && x.targetCount === 36)!
    expect(lb36.best.id).toBe('b'); expect(lb36.roundsShot).toBe(2); expect(lb36.averageTotal).toBeCloseTo(594)
    expect(b.find(x => x.targetCount === 40)!.best.id).toBe('c')
    expect(b.find(x => x.bowStyle === 'BB')!.best.id).toBe('d')
    expect(b.length).toBe(3)
  })
  it('tie goes to the first time shot', () => {
    const rs = [round('later', '2026-05-01', 18), round('first', '2026-04-01', 18)]
    expect(bests(rs)[0].best.id).toBe('first')
    expect(personalBestIds(rs).size).toBe(0)
  })
  it('personal bests mark rounds that beat every earlier one', () => {
    const rs = [
      round('1', '2026-01-01', 15), round('2', '2026-02-01', 17), round('3', '2026-03-01', 16), round('4', '2026-04-01', 18),
      round('5', '2026-04-01', 19, { created: '2026-04-01T10:00:00.000Z' }), round('6', '2026-05-01', 20, { targets: 40 }),
    ]
    expect([...personalBestIds(rs)].sort()).toEqual(['2', '4', '5'])
    expect(previousBest(rs[4], rs)?.id).toBe('4')
    expect(previousBest(rs[0], rs)).toBeUndefined()
  })
  it('best average ignores short rounds', () => {
    const b = bestAverages([round('short', '2026-01-01', 24, { targets: 10 }), round('half', '2026-02-01', 19, { targets: 18 }), round('full', '2026-03-01', 18)])
    expect(b.length).toBe(1); expect(b[0].round.id).toBe('half'); expect(b[0].average).toBeCloseTo(19)
  })
  it('trend is oldest first with a rolling average', () => {
    const rs = [round('c', '2026-03-01', 12), round('a', '2026-01-01', 10), round('b', '2026-02-01', 11), round('w', '2026-02-15', 20, { type: 'WOODSMAN' })]
    const t = trend(rs, 'BIG_GAME', 'LB', 2)
    expect(t.map(p => p.round.id)).toEqual(['a', 'b', 'c'])
    expect(t.map(p => p.average)).toEqual([10, 11, 12])
    expect(t.map(p => p.rolling)).toEqual([10, 10.5, 11.5])
    expect(trend(rs, 'WOODSMAN', null).length).toBe(1)
  })
  it('seasons are calendar years per type', () => {
    const rs = [
      round('a', '2025-12-31', 10, { location: 'Club A' }), round('b', '2026-01-01', 12, { location: 'Club A' }),
      round('c', '2026-06-01', 16, { targets: 40, location: 'club a ' }), round('d', '2026-07-01', 14, { bow: 'BB', location: 'Club B' }),
      round('w', '2026-08-01', 25, { targets: 20, type: 'WOODSMAN' }),
    ]
    const s = seasons(rs)
    expect(s.map(x => [x.year, x.type])).toEqual([[2026, 'BIG_GAME'], [2026, 'WOODSMAN'], [2025, 'BIG_GAME']])
    const bg = s[0]
    expect(bg.rounds).toBe(3); expect(bg.targets).toBe(112); expect(bg.locations).toBe(2); expect(bg.bestRound?.id).toBe('c')
    expect(bg.stats.averagePerAnimal).toBeCloseTo((432 + 640 + 504) / 112)
    expect(bg.byBow.map(b => b.bowStyle)).toEqual(['BB', 'LB'])
    expect(bg.personalBests).toBe(1)
    expect(s[1].stats.averagePerAnimal).toBeCloseTo(25)
  })
  it('periods', () => {
    const today = '2026-11-08'
    expect(inPeriod({ kind: '12m' }, '2025-11-09', today)).toBe(true)
    expect(inPeriod({ kind: '12m' }, '2025-11-08', today)).toBe(false)
    expect(inPeriod({ kind: 'year', year: 2025 }, '2025-01-01', today)).toBe(true)
    expect(inPeriod({ kind: 'between', from: '2026-03-01' }, '2026-03-01', today)).toBe(true)
    expect(inPeriod({ kind: 'between', from: '2026-03-01' }, '2026-02-28', today)).toBe(false)
    expect(years(['2025-05-01', '2026-01-01'])).toEqual([2026, 2025])
  })
})

describe('handicap', () => {
  const m: HandicapModel = { modelId: 1, dataThroughYear: 2026, yearZero: 2014, factors: {
    'BOW:LB': -1.36, 'SLOPE:LB': -0.25, 'BOW3D:LB': -0.07, 'BOWSEX:LB': -0.01, 'CLASS:F-ADULT': -0.40, 'CLASS:M-JUNIOR': -0.12 } }
  const lb: HArcher = { bowStyle: 'LB', archerClass: 'GENTS', ageGroup: 'ADULT' }
  const lbLady: HArcher = { ...lb, archerClass: 'LADIES' }
  const logit = (p: number) => Math.log(p / (1 - p))
  it('Unlimited Gents is unchanged', () => expect(convert(m, 600, 960, REFERENCE, 2026)).toBeCloseTo(600, 9))
  it('Longbow by the formula', () => {
    const off = -1.36 - 0.25 * 1.2 + 0.5 * -0.07
    const exp = 960 / (1 + Math.exp(-(logit(600 / 960) - off)))
    expect(convert(m, 600, 960, lb, 2026)).toBeCloseTo(exp, 9); expect(exp).toBeGreaterThan(800)
  })
  it('Ladies add class and bow terms', () => {
    const off = -1.36 - 0.25 * 1.2 + 0.5 * -0.07 - 0.40 - 0.01
    expect(convert(m, 500, 960, lbLady, 2026)).toBeCloseTo(960 / (1 + Math.exp(-(logit(500 / 960) - off))), 9)
  })
  it('years after the data use the last year', () => {
    expect(convert(m, 600, 960, lb, 2031)).toBeCloseTo(convert(m, 600, 960, lb, 2026), 9)
    expect(convert(m, 600, 960, lb, 2005)).toBeLessThan(convert(m, 600, 960, lb, 2026))
  })
  it('round trips', () => {
    const asLb = convert(m, 800, 960, REFERENCE, 2020, lb)
    expect(convert(m, asLb, 960, lb, 2020, REFERENCE)).toBeCloseTo(800, 6); expect(asLb).toBeLessThan(800)
  })
  it('handicap points are the same for the same performance', () => {
    const asUl = convert(m, 600, 960, lb, 2026)
    expect(handicapPoints(m, 600, 960, lb, 2026)).toBeCloseTo(handicapPoints(m, asUl, 960, REFERENCE, 2026), 6)
    expect(handicapPoints(m, 700, 960, lb, 2026)).toBeGreaterThan(handicapPoints(m, 600, 960, lb, 2026))
  })
  it('neutral is the middle', () => {
    const n = neutralOffset(m, 2026)
    expect(handicapPoints(m, 480, 960, REFERENCE, 2026)).toBeCloseTo(1000 / (1 + Math.exp(-(0 + n))), 6)
    expect(offset(m, lb, 2026)).toBeLessThan(0)
  })
  it('rating averages the latest ten', () => {
    const rs = Array.from({ length: 12 }, (_, i) => ({ shootDate: `2026-01-${String(i + 1).padStart(2, '0')}`, points: 481 + i, maxPoints: 960, archer: lb }))
    const r = rating(m, rs)!
    expect(r.rounds).toBe(10)
    const exp = rs.slice(2).reduce((s, h) => s + handicapPoints(m, h.points, 960, lb, 2026), 0) / 10
    expect(r.rating).toBe(Math.round(exp))
    expect(rating(m, rs.slice(0, 2))).toBeNull()
  })
})

describe('home layout', () => {
  it('usual order by default', () => { expect(DEFAULT_LAYOUT.order).toEqual([...TILES]); expect(isDefault(DEFAULT_LAYOUT)).toBe(true) })
  it('moves up and down', () => {
    const l = move(DEFAULT_LAYOUT, 'STATS', -1, [...TILES])
    expect(l.order.slice(1, 4)).toEqual(['JOIN', 'STATS', 'HISTORY'])
    expect(move(l, 'SCORE', -1, [...TILES])).toBe(l)
    expect(isDefault(l)).toBe(false)
  })
  it('skips tiles not shown', () => {
    const shown = TILES.filter(t => t !== 'ADMIN')
    expect(move(DEFAULT_LAYOUT, 'LOCATIONS', 1, shown)).toBe(DEFAULT_LAYOUT)
  })
  it('hides, but never Score a round', () => {
    const l = setHidden(setHidden(DEFAULT_LAYOUT, 'HANDICAP', true), 'SCORE', true)
    expect(l.hidden).toEqual(['HANDICAP'])
  })
  it('new tiles go in at their usual place', () => {
    const l = normalised({ order: ['SCORE', 'STATS', 'HISTORY', 'FRIENDS', 'LOCATIONS', 'ADMIN', 'OLD' as never], hidden: ['SCORE'] })
    expect(l.order).toEqual(['SCORE', 'JOIN', 'STATS', 'HANDICAP', 'HISTORY', 'FRIENDS', 'LOCATIONS', 'ADMIN'])
    expect(l.hidden).toEqual([])
  })
})
