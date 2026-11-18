// Stats analysis and weather (3.2.17). The same cases as the app's AnalysisTest.kt.
import { describe, expect, it } from 'vitest'
import { newRound, type Round } from '../src/domain/round'
import { withOverride, newTarget, type AnimalResult, type TargetScore } from '../src/domain/scoring'
import {
  arrowCounts, byLocation, byTargetType, comparedText, consistency, form, hitCounts, hitsOverTime, shootingOrder, streak, thirds, weatherSplits,
} from '../src/domain/analysis'
import { condition, isWet, isWindy, weatherFromJson, weatherLine, type Weather } from '../src/domain/weather'

const S = (arrowNo: number, hit: 'INNER_KILL' | 'KILL' | 'WOUND'): AnimalResult => ({ state: 'SCORED', arrowNo, hit })
const M: AnimalResult = { state: 'MISSED' }
const std = (n: number, a: AnimalResult): TargetScore => ({ targetNo: n, type: 'STANDARD', animals: [a], overridePoints: null, arrows: '' })

function bg(id: string, date: string, targets: TargetScore[], o: Partial<Round> = {}): Round {
  const r = newRound({ id, shootDate: date, targetCount: targets.length, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME' })
  return { ...r, status: 'COMPLETED', createdUtc: `${date}T09:00:00.000Z`, targets, ...o }
}
/** A round of [n] targets each overridden to [per] points. */
const flat = (id: string, date: string, per: number, n = 3, o: Partial<Round> = {}) =>
  bg(id, date, Array.from({ length: n }, (_, i) => withOverride(newTarget('BIG_GAME', i + 1), per)), o)

describe('stats analysis', () => {
  it('counts which arrow scored, and blanks', () => {
    const r = bg('a', '2026-05-01', [std(1, S(1, 'KILL')), std(2, S(2, 'WOUND')), std(3, M), std(4, S(3, 'KILL')),
      { targetNo: 5, type: 'PREDATOR_PREY', animals: [S(1, 'INNER_KILL'), S(2, 'KILL')], overridePoints: null, arrows: '' },
      withOverride(newTarget('BIG_GAME', 6), 20), { targetNo: 7, type: 'FUN', animals: [], overridePoints: 50, arrows: '' }])
    expect(arrowCounts([r])).toEqual({ first: 2, second: 2, third: 1, none: 1, total: 6 })
    expect(hitCounts([r])).toEqual({ innerKill: 1, kill: 3, wound: 1, miss: 1, total: 6 })
  })

  it('Woodsman counts per target', () => {
    const w = { ...newRound({ id: 'w', shootDate: '2026-05-01', targetCount: 3, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'WOODSMAN' }),
      status: 'COMPLETED' as const, targets: ['K', 'WWK', 'WMW'].map((a, i) => ({ ...newTarget('WOODSMAN', i + 1), arrows: a })) }
    expect(arrowCounts([w])).toEqual({ first: 1, second: 0, third: 1, none: 1, total: 3 })
    expect(hitCounts([w])).toEqual({ innerKill: 0, kill: 2, wound: 1, miss: 0, total: 3 })
  })

  it('hits over time: by year across years, by month within one', () => {
    const rs = [bg('a', '2025-06-01', [std(1, S(1, 'KILL'))]), bg('b', '2026-04-01', [std(1, M)]), bg('c', '2026-05-01', [std(1, S(1, 'WOUND'))])]
    expect(hitsOverTime(rs).map(p => [p.label, p.hits.total])).toEqual([['2025', 1], ['2026', 2]])
    expect(hitsOverTime(rs.slice(1)).map(p => p.label)).toEqual(['Apr 2026', 'May 2026'])
  })

  it('average per animal by target type', () => {
    const r = bg('a', '2026-05-01', [std(1, S(1, 'KILL')), std(2, S(1, 'INNER_KILL')),
      { targetNo: 3, type: 'PREDATOR_PREY', animals: [S(1, 'KILL'), S(2, 'WOUND')], overridePoints: null, arrows: '' },
      { targetNo: 4, type: 'FAN', animals: [S(1, 'WOUND'), M, S(1, 'KILL')], overridePoints: null, arrows: '' }])
    expect(byTargetType([r])).toEqual([
      { type: 'STANDARD', targets: 2, animals: 2, average: 22 },
      { type: 'PREDATOR_PREY', targets: 1, animals: 2, average: 15 },
      { type: 'FAN', targets: 1, animals: 3, average: 12 },
    ])
  })

  it('consistency, streaks and form', () => {
    const rs = [10, 12, 14, 16, 18, 17, 19].map((v, i) => flat(`r${i}`, `2026-0${i + 1}-01`, v))
    const c = consistency(rs)!
    expect(c.rounds).toBe(7); expect(c.average).toBeCloseTo(15.142857, 4); expect(c.spread).toBeCloseTo(3.287784, 4)
    expect(streak(rs)).toEqual({ average: c.average, current: 4, best: 4 })
    const f = form(rs, 'BIG_GAME')!
    expect(f.recent).toBeCloseTo(16.8); expect(f.before).toBeCloseTo(11); expect(f.direction).toBe('UP')
    expect(form(rs.slice(0, 5), 'BIG_GAME')).toBeNull()
    expect(consistency(rs.slice(0, 2))).toBeNull()
  })

  it('start, middle and finish follow the shooting order from the starting target', () => {
    const r = bg('a', '2026-05-01', [24, 24, 20, 20, 16, 16].map((p, i) => withOverride(newTarget('BIG_GAME', i + 1), p)), { startTarget: 5 })
    expect(shootingOrder(r)).toEqual([5, 6, 1, 2, 3, 4])
    expect(thirds([r])).toEqual({ start: 16, middle: 24, finish: 20 })
    expect(thirds([{ ...r, startTarget: null }])).toEqual({ start: 24, middle: 20, finish: 16 })
  })

  it('by location, most-shot first', () => {
    const rs = [flat('a', '2026-01-01', 18, 3, { locationName: 'Sherwood' }), flat('b', '2026-02-01', 20, 3, { locationName: 'sherwood ' }),
      flat('c', '2026-03-01', 22, 3, { locationName: 'Bowland' }), flat('d', '2026-04-01', 10)]
    expect(byLocation(rs).map(l => [l.name, l.rounds, l.average, l.best, l.bestRound.id])).toEqual([['Sherwood', 2, 19, 20, 'b'], ['Bowland', 1, 22, 22, 'c']])
  })

  it('weather lines and splits', () => {
    const wet: Weather = { tempC: 6.4, windKph: 30, gustKph: 52, rainMm: 3.2, code: 63 }
    const dry: Weather = { tempC: 15, windKph: 8, gustKph: null, rainMm: 0, code: 1 }
    expect(weatherLine(wet)).toBe('6°C · Rain, 3.2 mm · wind 19 mph, gusts 32 mph')
    expect(weatherLine(dry)).toBe('15°C · Partly cloudy · wind 5 mph')
    expect([isWet(wet), isWet(dry), isWindy(wet), isWindy(dry)]).toEqual([true, false, true, false])
    expect(condition(0)).toBe('Sunny'); expect(condition(95)).toBe('Thunder')
    expect(weatherFromJson({ tempC: null, windKph: null })).toBeNull()
    const rs = [flat('a', '2026-01-01', 16), flat('b', '2026-02-01', 20), flat('c', '2026-03-01', 22), flat('d', '2026-04-01', 10)]
    const w: Record<string, Weather> = { a: wet, b: dry, c: dry }
    const s = weatherSplits(rs, r => w[r.id])
    expect(s[0]).toMatchObject({ label: 'Rain', yes: { rounds: 1, average: 16 }, no: { rounds: 2, average: 21 } })
    expect(s[2]).toMatchObject({ label: 'Temperature', yes: { rounds: 1 }, no: { rounds: 2 } })
  })

  it('compared with others needs 20 rounds', () => {
    expect(comparedText(0.704, 25, 'Gents Adult Longbow')).toBe('Better than 70% of Gents Adult Longbow rounds')
    expect(comparedText(0.7, 19, 'x')).toBeNull()
  })
})
