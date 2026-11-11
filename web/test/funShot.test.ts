// Fun shots (3.1, Pete): a Big Game target with unusual scoring, typed in. In the round total, not in any stats.
import { describe, expect, it } from 'vitest'
import { funPoints, newRound, roundStats, roundTotal, statsTargetCount, statsTotal, targetsLine, type Round } from '../src/domain/round'
import { isComplete, maxPoints, newTarget, totalPoints, withOverride, withType } from '../src/domain/scoring'
import { handicapEntry } from '../src/domain/handicap'
import { bests, personalBestIds } from '../src/domain/stats'
import { rowToTarget, targetToRow } from '../src/domain/paper'
import { decodeTarget, encodeTarget } from '../src/domain/liveCheck'
import { roundsCsv, targetsCsv } from '../src/domain/export'

const kill = (n: number) => ({ ...newTarget('BIG_GAME', n), animals: [{ state: 'SCORED' as const, arrowNo: 1, hit: 'KILL' as const }] })
const fun = (n: number, points: number | null) => withOverride(withType(newTarget('BIG_GAME', n), 'FUN'), points)

function round(id: string, date: string, targets: Round['targets']): Round {
  const r = newRound({ id, shootDate: date, targetCount: targets.length, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME' })
  return { ...r, status: 'COMPLETED', createdUtc: `${date}T10:00:00.000Z`, targets }
}

describe('fun shots', () => {
  it('a fun shot is scored once its score is typed in, and has no maximum for stats', () => {
    const t = withType(kill(1), 'FUN')
    expect(t.animals).toEqual([]); expect(t.overridePoints).toBeNull()
    expect(isComplete(t)).toBe(false); expect(totalPoints(t)).toBe(0)
    expect(isComplete(fun(1, 0))).toBe(true); expect(totalPoints(fun(1, 45))).toBe(45); expect(maxPoints(fun(1, 45))).toBe(0)
    // Switching back starts the target afresh (the typed score isn't an override).
    expect(withType(fun(1, 45), 'STANDARD').overridePoints).toBeNull()
  })

  it('counts in the round total but not in the stats', () => {
    const r = round('a', '2026-11-01', [kill(1), kill(2), fun(3, 45)])
    expect(roundTotal(r)).toBe(85); expect(funPoints(r)).toBe(45)
    expect(statsTotal(r)).toBe(40); expect(statsTargetCount(r)).toBe(2)
    const s = roundStats(r)
    expect(s.total).toBe(85); expect(s.funShots).toBe(1); expect(s.funPoints).toBe(45)
    expect(s.targetsScored).toBe(2); expect(s.averagePerAnimal).toBe(20); expect(s.shareOfMax).toBeCloseTo(40 / 48)
    expect(s.overrides).toBe(0)
    expect(targetsLine(r)).toBe('3 targets · 1 fun shot')
  })

  it('is left out of the handicap and personal bests', () => {
    const r = round('a', '2026-11-01', [kill(1), kill(2), fun(3, 45)])
    expect(handicapEntry(r)).toMatchObject({ points: 40, targetCount: 2 })
    // A round of 2 kills and a fun shot isn't better than a round of 3 kills just because of the fun shot.
    const plain = round('b', '2026-10-01', [kill(1), kill(2), { ...kill(3), animals: [{ state: 'SCORED', arrowNo: 1, hit: 'INNER_KILL' }] }])
    const withFun = round('c', '2026-11-02', [kill(1), { ...kill(2), animals: [{ state: 'SCORED', arrowNo: 1, hit: 'INNER_KILL' }] }, fun(3, 45)])
    expect(bests([plain, withFun]).map(b => [b.targetCount, statsTotal(b.best)])).toEqual([[2, 44], [3, 64]])
    expect(personalBestIds([plain, withFun]).size).toBe(0)
  })

  it('the paper sheet takes any whole number for a fun shot', () => {
    const ok = rowToTarget('BIG_GAME', 5, { type: 'FUN', cells: ['37'] })
    expect('target' in ok && ok.target).toMatchObject({ type: 'FUN', overridePoints: 37, animals: [] })
    expect('error' in rowToTarget('BIG_GAME', 5, { type: 'FUN', cells: ['1000'] })).toBe(true)
    expect('error' in rowToTarget('BIG_GAME', 5, { type: 'FUN', cells: ['-3'] })).toBe(true)
    expect(targetToRow(fun(5, 37))).toEqual({ type: 'FUN', cells: ['37'] })
  })

  it('goes through the QR check and the exports', () => {
    expect(encodeTarget(fun(4, 30))).toBe('X=30')
    expect(decodeTarget(4, 'X=30')).toEqual(fun(4, 30))
    expect(decodeTarget(4, 'X')).toEqual(fun(4, null))
    const r = round('a', '2026-11-01', [kill(1), kill(2), fun(3, 45)])
    expect(roundsCsv([r]).split('\r\n')[1]).toContain(',85,45,48,20,')
    expect(targetsCsv([r])).toContain('Fun shot: 45 (not in stats)')
  })
})
