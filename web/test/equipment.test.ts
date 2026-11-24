// Equipment and handicap over time (3.2.24). The same cases as the app's EquipmentTest.kt and HandicapTest.kt.
import { describe, expect, it } from 'vitest'
import { newRound, type Round } from '../src/domain/round'
import { withOverride, newTarget } from '../src/domain/scoring'
import { byEquipment } from '../src/domain/analysis'
import { matchesFilter, resolveFilter, DEFAULT_PERIOD, type StatsFilter } from '../src/domain/statsFilter'
import { handicapHistory, type HEntry } from '../src/domain/handicap'
import {
  arrowDetail, arrowLabel, arrowProblems, arrowsFor, arrowsFromForm, bowDetail, bowForm, bowFromForm, bowLabel, bowName, bowProblems,
  bowsFor, chooseEquipment, NONE, emptyArrowForm, emptyBowForm, fletches, lastUsed, num, parseSpine, showArrows, showBows,
  type ArrowSet, type Bow,
} from '../src/domain/equipment'

const sp = (s: string) => { const x = parseSpine(s); return x ? [x.text, x.kind] : null }

describe('spine', () => {
  it('carbon', () => {
    expect(sp('500')).toEqual(['500', 'CARBON']); expect(sp(' 0.500 ')).toEqual(['500', 'CARBON'])
    expect(sp('.340')).toEqual(['340', 'CARBON']); expect(sp('1000')).toEqual(['1000', 'CARBON']); expect(sp('Spine 400')).toEqual(['400', 'CARBON'])
  })
  it('wood', () => {
    for (const s of ['45-50', '45/50', '45 to 50', '45–50#']) expect(sp(s)).toEqual(['45-50', 'WOOD'])
    expect(sp('50 55 lbs')).toEqual(['50-55', 'WOOD']); expect(sp('45#')).toEqual(['45', 'WOOD'])
    expect(sp('55')).toEqual(['55', 'WOOD']); expect(sp('100-105')).toEqual(['100-105', 'WOOD'])
  })
  it('aluminium', () => {
    expect(sp('2213')).toEqual(['2213', 'ALUMINIUM']); expect(sp('22-13')).toEqual(['2213', 'ALUMINIUM'])
    expect(sp('16 18')).toEqual(['1618', 'ALUMINIUM']); expect(sp('1816')).toEqual(['1816', 'ALUMINIUM'])
  })
  it('nonsense', () => {
    for (const s of ['', '   ', 'stiff', '5', '50-45', '12345', '500#']) expect(parseSpine(s)).toBeNull()
  })
})

const longbow: Bow = { id: 'b1', type: 'LONGBOW', make: 'Border', model: 'Hex 7', poundageLb: 45.5, bowStyles: ['LB', 'HT'], updatedUtc: '' }
const flatbow: Bow = { id: 'b2', type: 'FLATBOW', make: 'Bodnik', model: null, poundageLb: 40, bowStyles: ['AFB', 'HT'], updatedUtc: '' }
const gone: Bow = { id: 'b3', type: 'LONGBOW', make: 'Old', poundageLb: 50, bowStyles: ['LB'], updatedUtc: '', deleted: true }
const bows = [longbow, flatbow, gone]
const cedar: ArrowSet = { id: 'a1', make: 'Port Orford', model: 'Cedar', spine: '45-50', bowIds: ['b1', 'b2'], updatedUtc: '' }
const pine: ArrowSet = { id: 'a2', make: 'Sitka', model: 'Pine', bowIds: ['b1'], updatedUtc: '' }
const arrows = [cedar, pine]

describe('equipment', () => {
  it('names and details', () => {
    expect(bowName(longbow)).toBe('Border Hex 7'); expect(bowDetail(longbow)).toBe('Longbow · 45.5 lb'); expect(bowLabel(longbow)).toBe('Border Hex 7 (45.5 lb)')
    expect(bowName({ id: 'x', type: 'RECURVE', bowStyles: ['BB'], updatedUtc: '' })).toBe('Recurve')
    const full: ArrowSet = { ...cedar, lengthIn: 29.5, arrowGrains: 480, pointGrains: 125, fletchCount: 3, fletchMaterial: 'FEATHER', fletchLengthIn: 5, fletchColours: 'barred grey' }
    expect(arrowDetail(full)).toBe('29.5″ · 480 gr · spine 45-50 · 125 gr point · 3 feathers, 5″, barred grey')
    expect(arrowLabel(cedar)).toBe('Port Orford Cedar (45-50)')
    expect(fletches({ id: 'x', bowIds: ['b'], updatedUtc: '', fletchCount: 1, fletchMaterial: 'FEATHER' })).toBe('1 feather')
  })
  it('bows for a bow style, arrows for a bow', () => {
    expect(bowsFor(bows, 'LB').map(b => b.id)).toEqual(['b1'])
    expect(bowsFor(bows, 'HT').map(b => b.id)).toEqual(['b2', 'b1'])
    expect(bowsFor(bows, 'BB')).toEqual([])
    expect(arrowsFor(arrows, 'b2').map(a => a.id)).toEqual(['a1'])
    expect(arrowsFor(arrows, 'b1').map(a => a.id)).toEqual(['a1', 'a2'])
  })
  it('a choice of one is hidden and used', () => {
    const c = chooseEquipment(bows, arrows, 'AFB')
    expect([c.bowId, showBows(c), c.arrowId, showArrows(c)]).toEqual(['b2', false, 'a1', false])
  })
  it('starts on the last used, then what is picked', () => {
    const none = chooseEquipment(bows, arrows, 'HT')
    expect([none.bowId, showBows(none), showArrows(none)]).toEqual([null, true, false])
    const last = chooseEquipment(bows, arrows, 'HT', { lastBowId: 'b1', lastArrowId: 'a2' })
    expect([last.bowId, last.arrowId, showArrows(last)]).toEqual(['b1', 'a2', true])
    const picked = chooseEquipment(bows, arrows, 'HT', { bowId: 'b2', arrowId: 'a2', lastBowId: 'b1', lastArrowId: 'a2' })
    expect([picked.bowId, picked.arrowId]).toEqual(['b2', 'a1'])
    expect(chooseEquipment(bows, arrows, 'LB', { lastBowId: 'b3' }).bowId).toBe('b1')
    expect(chooseEquipment(bows, arrows, null)).toEqual({ bows: [], bowId: null, arrows: [], arrowId: null })
    const picked0 = chooseEquipment(bows, arrows, 'HT', { bowId: NONE, lastBowId: 'b1' })
    expect([picked0.bowId, showBows(picked0), picked0.arrows]).toEqual([null, true, []])
    expect(chooseEquipment(bows, arrows, 'AFB', { arrowId: NONE }).arrowId).toBeNull()
  })
  it('bow form', () => {
    expect(bowProblems(emptyBowForm())).toEqual(['Pick the bow type.', 'Give the bow a make or model.'])
    const f = { type: 'LONGBOW' as const, make: ' Border ', model: 'Hex  7', poundage: '45,5 lb', bowStyles: ['LB'] }
    expect(bowFromForm(f, 'id', 't')).toEqual({ id: 'id', type: 'LONGBOW', make: 'Border', model: 'Hex 7', poundageLb: 45.5, bowStyles: ['LB'], updatedUtc: 't' })
    expect(bowProblems({ ...f, poundage: 'heavy' })).toEqual(['Poundage should be a number.'])
    expect(bowProblems({ ...f, poundage: '300' })).toEqual(['Poundage must be between 5 and 250.'])
    expect(bowForm(bowFromForm(f, 'id', 't')!)).toEqual({ ...f, make: 'Border', model: 'Hex 7', poundage: '45.5' })
  })
  it('arrow form', () => {
    const f = { ...emptyArrowForm(), make: 'Easton', model: 'Axis', length: '29.25"', arrowWeight: '410', spine: '0.500', fletchCount: '3',
      fletchMaterial: 'VANE' as const, fletchLength: '2', pointWeight: '100 gr', bowIds: ['b1'] }
    const a = arrowsFromForm(f, 'a', 't')!
    expect([a.spine, a.lengthIn, a.pointGrains, a.fletchCount]).toEqual(['500', 29.25, 100, 3])
    expect(arrowProblems({ ...f, spine: 'stiff' })).toEqual(['Spine should look like 500 (carbon), 45-50 (wood) or 2213 (aluminium).'])
    expect(arrowProblems({ ...f, fletchCount: '2.5' })).toEqual(['Fletches must be a whole number from 1 to 8.'])
    expect(arrowProblems({ ...f, bowIds: [] })).toEqual([])   // 3.2.27: arrows with no bow can be kept
    expect(arrowProblems({ ...emptyArrowForm(), bowIds: ['b'] })).toEqual(['Give the arrows a make or model.'])
  })
  it('numbers', () => { expect([num(45), num(45.5), num(29.25)]).toEqual(['45', '45.5', '29.25']) })
})

function round(id: string, created: number, bowStyle: string, bowId: string | null, arrowId: string | null): Round {
  const r = newRound({ id, shootDate: '2026-05-01', targetCount: 1, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle, type: 'BIG_GAME' })
  return { ...r, status: 'COMPLETED', createdUtc: `2026-05-01T09:00:0${created}.000Z`, targets: [withOverride(newTarget('BIG_GAME', 1), 20)], bowId, arrowId }
}

describe('equipment in rounds', () => {
  it('last used per bow style', () => {
    const rs = [round('r1', 1, 'HT', 'b1', 'a1'), round('r2', 3, 'HT', 'b2', 'a1'), round('r3', 4, 'HT', null, null), round('r4', 5, 'LB', 'b1', 'a2')]
    expect(lastUsed(rs, 'HT')).toEqual({ bowId: 'b2', arrowId: 'a1' })
    expect(lastUsed(rs, 'LB')).toEqual({ bowId: 'b1', arrowId: 'a2' })
    expect(lastUsed(rs, 'BB')).toEqual({ bowId: null, arrowId: null })
  })
  it('compare by bow and arrows', () => {
    const rs = [round('r1', 1, 'LB', 'b1', 'a1'), round('r2', 2, 'LB', 'b1', 'a2'), round('r3', 3, 'LB', 'b2', 'a1'), round('r4', 4, 'LB', null, null)]
    const byBow = byEquipment(rs, r => r.bowId)
    expect(byBow.map(s => [s.id, s.rounds])).toEqual([['b1', 2], ['b2', 1]])
    expect(byBow[0].average).toBeCloseTo(20)
    expect(byEquipment(rs, r => r.arrowId).map(s => [s.id, s.rounds])).toEqual([['a1', 2], ['a2', 1]])
  })
  it('stats filter by bow and arrows', () => {
    const rs = [round('r1', 1, 'LB', 'b1', 'a1'), round('r2', 2, 'LB', 'b2', 'a1')]
    const f: StatsFilter = { types: ['BIG_GAME'], bows: ['LB'], period: DEFAULT_PERIOD, bowId: 'b1' }
    expect(rs.filter(r => matchesFilter(f, r, '2026-06-01')).map(r => r.id)).toEqual(['r1'])
    expect(rs.filter(r => matchesFilter({ ...f, bowId: null, arrowId: 'a1' }, r, '2026-06-01')).map(r => r.id)).toEqual(['r1', 'r2'])
    expect(resolveFilter(f, rs, null).bowId).toBe('b1')
    expect(resolveFilter({ ...f, bowId: 'gone' }, rs, null).bowId).toBeUndefined()
  })
})

describe('handicap over time', () => {
  const e = (day: number, points: number, bowStyle = 'LB'): HEntry =>
    ({ shootDate: `2026-01-${String(day + 1).padStart(2, '0')}`, order: '0', bowStyle, points, targetCount: 40 })
  it('what it was after each round, from the 3rd', () => {
    const es = [e(4, 900), e(1, 600), e(2, 700), e(3, 800), e(5, 100, 'UL')]
    expect(handicapHistory(es, 'LB').map(p => [p.entry.shootDate, p.handicap])).toEqual([['2026-01-04', 260], ['2026-01-05', 160]])
    expect(handicapHistory(es, 'UL')).toEqual([])
  })
})
