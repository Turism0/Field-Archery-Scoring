// Score export (2.7.03): CSV quoting, both CSV layouts and the JSON.
import { describe, expect, it } from 'vitest'
import { newRound, type Round } from '../src/domain/round'
import { newTarget, withOverride } from '../src/domain/scoring'
import { csvField, exportFile, roundsCsv, roundsJson, targetsCsv, ROUND_HEADER, TARGET_HEADER } from '../src/domain/export'

function bigGame(): Round {
  const r = newRound({ id: 'r1', shootDate: '2026-10-04', targetCount: 3, archerClass: 'GENTS', ageGroup: 'ADULT', bowStyle: 'LB', type: 'BIG_GAME', locationName: 'Sherwood, "Top" field', startTarget: 2 })
  return { ...r, status: 'COMPLETED', targets: [
    { ...newTarget('BIG_GAME', 1), animals: [{ state: 'SCORED', arrowNo: 1, hit: 'INNER_KILL' }] },
    { ...newTarget('BIG_GAME', 2), animals: [{ state: 'MISSED' }] },
    withOverride(newTarget('BIG_GAME', 3), 12),
  ] }
}
function woodsman(): Round {
  const r = newRound({ id: 'w1', shootDate: '2026-10-05', targetCount: 2, archerClass: 'LADIES', ageGroup: 'JUNIOR', bowStyle: 'BB', type: 'WOODSMAN' })
  return { ...r, status: 'COMPLETED', targets: [{ ...newTarget('WOODSMAN', 1), arrows: 'WK' }, { ...newTarget('WOODSMAN', 2), arrows: 'MMM' }] }
}
const lines = (csv: string) => csv.trimEnd().split('\r\n')

describe('export', () => {
  it('quotes fields with commas and quotes', () => {
    expect(csvField('a,b')).toBe('"a,b"'); expect(csvField('say "hi"')).toBe('"say ""hi"""')
    expect(csvField(null)).toBe(''); expect(csvField(1.25)).toBe('1.25'); expect(csvField(24)).toBe('24')
  })
  it('one row per round', () => {
    const l = lines(roundsCsv([bigGame(), woodsman()]))
    expect(l.length).toBe(3); expect(l[0]).toBe(ROUND_HEADER.join(','))
    expect(l[1].startsWith('2026-10-04,Big Game,3,3,2,"Sherwood, ""Top"" field",')).toBe(true)
    const row = l[1].split(',')
    expect(l[1]).toContain(',Gents,Adult,36,,72,')   // 24 + 0 + 12 of 3 × 24
    expect(row[row.length - 1]).toBe('r1'); expect(row[row.length - 2]).toBe('No')
    expect(l[2]).toContain('Woodsman,2,2,,,')
    expect(l[2]).toContain(',Ladies,Junior,25,,60,')
  })
  it('one row per target', () => {
    const l = lines(targetsCsv([bigGame(), woodsman()]))
    expect(l[0]).toBe(TARGET_HEADER.join(',')); expect(l.length).toBe(6)
    expect(l[1]).toContain(',36,1,Standard,1,24,24,No,1,Inner kill,"Inner kill, 1st arrow",r1')
    expect(l[2]).toContain(',2,Standard,1,0,24,No,,Miss,Miss,r1')
    expect(l[3]).toContain(',3,Standard,1,12,24,Yes,,,Overridden: 12,r1')
    expect(l[4]).toContain(',1,Woodsman,1,25,30,No,2,Kill,')
    expect(l[5]).toContain(',2,Woodsman,1,0,30,No,,Miss,')
  })
  it('JSON is the server layout plus id and names', () => {
    const j = JSON.parse(roundsJson([bigGame()], '2026-11-10T10:00:00.000Z', '2.7.03'))
    expect(j.exportedUtc).toBe('2026-11-10T10:00:00.000Z'); expect(j.rounds.length).toBe(1)
    const r = j.rounds[0]
    expect(r.roundId).toBe('r1'); expect(r.totalPoints).toBe(36); expect(r.bowName).toBe('Longbow'); expect(r.startTarget).toBe(2)
    expect(r.targets[0].animals[0]).toMatchObject({ arrowNo: 1, hit: 'INNER_KILL', points: 24 })
  })
  it('file names and Excel BOM', () => {
    const o = { today: '2026-11-10', nowUtc: 'x', appVersion: '2.7.03' }
    expect(exportFile('CSV_ROUNDS', [bigGame()], o).name).toBe('field-archery-round-2026-10-04.csv')
    expect(exportFile('CSV_TARGETS', [bigGame(), woodsman()], o).name).toBe('field-archery-rounds-2026-11-10-targets.csv')
    expect(exportFile('JSON', [bigGame(), woodsman()], o).name).toBe('field-archery-rounds-2026-11-10.json')
    expect(exportFile('CSV_ROUNDS', [bigGame()], o).text.charCodeAt(0)).toBe(0xfeff)
  })
})
