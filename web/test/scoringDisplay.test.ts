// What the scoring screen shows (3.2.20). The same cases as the app's ScoringDisplayTest.kt.
import { describe, expect, it } from 'vitest'
import { DEFAULT_STATS_DISPLAY, decodeDisplay, encodeDisplay, forCard } from '../src/domain/scoringDisplay'

describe('scoring display options', () => {
  it('defaults: total, average and hits on, remaining and max off', () => {
    const d = decodeDisplay(null)
    expect(d.mine).toEqual({ total: true, average: true, hits: true, remaining: false, maxPossible: false })
    expect(d.others).toEqual(d.mine)
    expect(encodeDisplay(d)).toBe('11100|11100')
  })
  it("3.2.18's switch carries over to both cards", () => {
    expect(encodeDisplay(decodeDisplay(null, true))).toBe('11111|11111')
  })
  it('saved choices round trip, mine and others separately', () => {
    const d = { mine: { total: false, average: false, hits: false, remaining: false, maxPossible: false }, others: { ...DEFAULT_STATS_DISPLAY, remaining: true } }
    expect(encodeDisplay(d)).toBe('00000|11110')
    expect(decodeDisplay(encodeDisplay(d), true)).toEqual(d)
    expect(forCard(d, true)).toBe(d.mine); expect(forCard(d, false)).toBe(d.others)
  })
  it('junk falls back to the defaults', () => {
    expect(encodeDisplay(decodeDisplay('1x100|11100'))).toBe('11100|11100')
    expect(encodeDisplay(decodeDisplay('111'))).toBe('11100|11100')
  })
})
