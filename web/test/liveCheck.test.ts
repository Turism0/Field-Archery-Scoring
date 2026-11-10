// Live groups with no signal (2.8): the same cases as the app's LiveCheckQrTest and LiveRulesTest (cells, checks by hand).
import { describe, expect, it } from 'vitest'
import { newTarget, withAnimalResult, type Hit, type TargetScore } from '../src/domain/scoring'
import {
  MATCHED, MISMATCH, WAITING, cells, checkProblem, compareCheck, decodeCheck, dueManualCheck, encodeCheck, mergeEntries, short,
  type CheckEntry,
} from '../src/domain/liveCheck'

const group = '3f2a9c1b-0000-4000-8000-000000000001'
const me = '77d0e1aa-0000-4000-8000-000000000002'
const sue = '5e5e5e5e-0000-4000-8000-000000000003'
const dave = '9b1c22e0-0000-4000-8000-000000000004'
const bob = 'b0b0b0b0-0000-4000-8000-000000000005'

const std = (n: number, arrow: number, hit: Hit) => withAnimalResult(newTarget('BIG_GAME', n), 0, { state: 'SCORED', arrowNo: arrow, hit })
const pp: TargetScore = { targetNo: 7, type: 'PREDATOR_PREY', animals: [{ state: 'SCORED', arrowNo: 1, hit: 'KILL' }, { state: 'MISSED' }], overridePoints: null, arrows: '' }
const fan: TargetScore = { targetNo: 9, type: 'FAN', animals: [{ state: 'SCORED', arrowNo: 2, hit: 'WOUND' }, { state: 'SCORED', arrowNo: 1, hit: 'INNER_KILL' }, { state: 'PENDING' }], overridePoints: null, arrows: '' }
const wood: TargetScore = { targetNo: 8, type: 'WOODSMAN', animals: [], overridePoints: null, arrows: 'WK' }
const over: TargetScore = { ...newTarget('BIG_GAME', 10), overridePoints: 12 }

describe('check QR', () => {
  it('encodes the agreed text (same as the app)', () => {
    expect(encodeCheck(group, me, [{ card: dave, target: std(6, 1, 'KILL') }, { card: dave, target: pp }, { card: dave, target: wood }, { card: dave, target: fan }, { card: dave, target: over }]))
      .toBe('fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.S1K;7.9b1c22e0.P1K,M;8.9b1c22e0.WWK;9.9b1c22e0.F2W,1I,-;10.9b1c22e0.S-=12')
  })
  it('round trips', () => {
    const list = [{ card: dave, target: std(6, 3, 'WOUND') }, { card: dave, target: pp }, { card: bob, target: wood }, { card: bob, target: fan }, { card: dave, target: over }]
    const p = decodeCheck(encodeCheck(group, me, list))!
    expect(p.group).toBe('3f2a9c1b'); expect(p.scorer).toBe('77d0e1aa')
    for (const e of list) expect(p.items.find(i => i.targetNo === e.target.targetNo && i.card === short(e.card))!.target).toEqual(e.target)
  })
  it('rejects other text', () => {
    expect(decodeCheck('fieldarchery:join:ABC234')).toBeNull()
    expect(decodeCheck('fieldarchery:check:1:short:77d0e1aa:')).toBeNull()
    expect(decodeCheck('fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.S4K')).toBeNull()
    expect(decodeCheck('fieldarchery:check:1:3f2a9c1b:77d0e1aa:6.9b1c22e0.X1K')).toBeNull()
    expect(decodeCheck('fieldarchery:check:1:3f2a9c1b:77d0e1aa:')!.items).toEqual([])
  })
  it('compares matches, differences and missing', () => {
    const theirs = decodeCheck(encodeCheck(group, sue, [
      { card: dave, target: std(6, 1, 'KILL') }, { card: dave, target: std(7, 1, 'WOUND') }, { card: bob, target: std(6, 1, 'INNER_KILL') },
      { card: dave, target: newTarget('BIG_GAME', 8) },
    ]))!
    const r = compareCheck({
      [`6|${dave}`]: std(6, 1, 'KILL'), [`7|${dave}`]: std(7, 1, 'KILL'), [`6|${bob}`]: std(6, 1, 'INNER_KILL'),
      [`8|${dave}`]: std(8, 2, 'KILL'), [`9|${dave}`]: std(9, 1, 'KILL'),
    }, theirs)
    expect(r.matched.sort()).toEqual([`6|${bob}`, `6|${dave}`].sort())
    expect(r.differences.map(d => [d.targetNo, d.cardMemberId])).toEqual([[7, dave]])
    expect(r.notInTheirs).toEqual([`8|${dave}`, `9|${dave}`])
  })
  it('same points, different hits differ', () => {
    const a: TargetScore = { targetNo: 5, type: 'FAN', animals: [{ state: 'SCORED', arrowNo: 1, hit: 'KILL' }, { state: 'MISSED' }], overridePoints: null, arrows: '' }
    const b: TargetScore = { targetNo: 5, type: 'FAN', animals: [{ state: 'SCORED', arrowNo: 1, hit: 'WOUND' }, { state: 'SCORED', arrowNo: 3, hit: 'WOUND' }], overridePoints: null, arrows: '' }
    expect(compareCheck({ [`5|${dave}`]: a }, decodeCheck(encodeCheck(group, sue, [{ card: dave, target: b }]))!).differences.length).toBe(1)
  })
  it('problems', () => {
    const scorers: Record<string, string[]> = { [dave]: [me, sue], [bob]: [me, sue] }
    const p = (text: string) => checkProblem(decodeCheck(text)!, group, me, [dave, bob], c => scorers[c] ?? [])
    expect(p(encodeCheck(group, sue, []))).toBeNull()
    expect(p(encodeCheck('ffffffff-0000', sue, []))).toContain('different group')
    expect(p(encodeCheck(group, me, []))).toContain('own QR')
    expect(p(encodeCheck(group, bob, []))).toContain("doesn't score")
  })
})

describe('matching with no signal', () => {
  const e = (n: number, card: string, scorer: string, t: TargetScore = std(n, 1, 'KILL'), at = '2026-11-10T10:00:00.000Z'): CheckEntry =>
    ({ targetNo: n, cardMemberId: card, scorerMemberId: scorer, target: t, enteredUtc: at })
  const two = { [dave]: [me, sue] }
  it('cells', () => {
    expect(cells([e(1, dave, me)], two)[`1|${dave}`]).toBe(WAITING)
    expect(cells([e(1, dave, me), e(1, dave, sue)], two)[`1|${dave}`]).toBe(MATCHED)
    expect(cells([e(1, dave, me), e(1, dave, sue, std(1, 1, 'WOUND'))], two)[`1|${dave}`]).toBe(MISMATCH)
    expect(cells([e(1, dave, me)], { [dave]: [me] })[`1|${dave}`]).toBe(MATCHED)
  })
  it('newest entry wins', () => {
    const merged = mergeEntries([e(1, dave, me, std(1, 1, 'KILL'), '2026-11-10T10:00:00.000Z')], [e(1, dave, me, std(1, 1, 'WOUND'), '2026-11-10T10:05:00.000Z')])
    expect(merged.length).toBe(1); expect(merged[0].target.animals[0]).toMatchObject({ hit: 'WOUND' })
  })
  it('a check by hand is due after five unchecked targets', () => {
    const four = [1, 2, 3, 4].map(n => e(n, dave, me))
    expect(dueManualCheck(me, four, two, new Set())).toEqual([])
    const six = [1, 2, 3, 4, 5, 6].map(n => e(n, dave, me))
    expect(dueManualCheck(me, six, two, new Set())).toEqual([1, 2, 3, 4, 5])
    expect(dueManualCheck(me, six, two, new Set([1, 2].map(n => `${n}|${dave}`)))).toEqual([])
    expect(dueManualCheck(me, [...six, ...[1, 2].map(n => e(n, dave, sue))], two, new Set())).toEqual([])
  })
})
