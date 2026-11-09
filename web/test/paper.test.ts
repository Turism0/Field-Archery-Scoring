// Typing in a paper scorecard (2.6.02).
import { describe, expect, it } from 'vitest'
import { cellDisabled, emptyRow, rowToTarget, targetToRow, withRowType } from '../src/domain/paper'
import { isComplete, totalPoints, type TargetScore } from '../src/domain/scoring'

const ok = (r: ReturnType<typeof rowToTarget>): TargetScore => { if ('error' in r) throw new Error(r.error); return r.target }

describe('paper entry', () => {
  it('Big Game points give the arrow and hit', () => {
    const t = ok(rowToTarget('BIG_GAME', 1, { type: 'STANDARD', cells: ['14'] }))
    expect(t.animals[0]).toEqual({ state: 'SCORED', arrowNo: 2, hit: 'KILL' })
    expect(totalPoints(t)).toBe(14); expect(isComplete(t)).toBe(true)
    expect(ok(rowToTarget('BIG_GAME', 1, { type: 'STANDARD', cells: ['0'] })).animals[0]).toEqual({ state: 'MISSED' })
    expect(isComplete(ok(rowToTarget('BIG_GAME', 1, emptyRow('BIG_GAME'))))).toBe(false)
  })
  it('rejects scores that do not exist', () => {
    expect('error' in rowToTarget('BIG_GAME', 1, { type: 'STANDARD', cells: ['18'] })).toBe(true)
    expect('error' in rowToTarget('BIG_GAME', 1, { type: 'STANDARD', cells: ['x'] })).toBe(true)
  })
  it('predator / prey: the prey gets the arrows left', () => {
    const t = ok(rowToTarget('BIG_GAME', 1, { type: 'PREDATOR_PREY', cells: ['20', '14'] }))
    expect(totalPoints(t)).toBe(34); expect(isComplete(t)).toBe(true)
    expect('error' in rowToTarget('BIG_GAME', 1, { type: 'PREDATOR_PREY', cells: ['10', '14'] })).toBe(true)   // arrow 2 used: 1 left
    const missed = ok(rowToTarget('BIG_GAME', 1, { type: 'PREDATOR_PREY', cells: ['0', ''] }))
    expect(isComplete(missed)).toBe(true); expect(totalPoints(missed)).toBe(0)
    expect(cellDisabled({ type: 'PREDATOR_PREY', cells: ['8', ''] }, 'BIG_GAME', 1)).toBe(true)
    expect(cellDisabled({ type: 'PREDATOR_PREY', cells: ['20', ''] }, 'BIG_GAME', 1)).toBe(false)
  })
  it('fans and totals by hand', () => {
    const fan = withRowType(emptyRow('BIG_GAME'), 'FAN', 3)
    expect(fan.cells.length).toBe(3)
    expect(totalPoints(ok(rowToTarget('BIG_GAME', 1, { ...fan, cells: ['24', '16', '0'] })))).toBe(40)
    const hand = ok(rowToTarget('BIG_GAME', 1, { type: 'STANDARD', cells: ['=17'] }))
    expect(hand.overridePoints).toBe(17); expect(isComplete(hand)).toBe(true)
    expect('error' in rowToTarget('BIG_GAME', 1, { type: 'STANDARD', cells: ['=30'] })).toBe(true)
  })
  it('Woodsman takes arrows, or a number by hand', () => {
    const t = ok(rowToTarget('WOODSMAN', 1, { type: 'WOODSMAN', cells: ['WK'] }))
    expect(totalPoints(t)).toBe(25); expect(isComplete(t)).toBe(true)
    expect(ok(rowToTarget('WOODSMAN', 1, { type: 'WOODSMAN', cells: ['20'] })).overridePoints).toBe(20)
    expect('error' in rowToTarget('WOODSMAN', 1, { type: 'WOODSMAN', cells: ['KW'] })).toBe(true)
  })
  it('round trips for editing', () => {
    for (const row of [{ type: 'PREDATOR_PREY' as const, cells: ['16', '10'] }, { type: 'STANDARD' as const, cells: ['=12'] }]) {
      expect(targetToRow(ok(rowToTarget('BIG_GAME', 1, row)))).toEqual(row)
    }
    expect(targetToRow(ok(rowToTarget('WOODSMAN', 1, { type: 'WOODSMAN', cells: ['MK'] })))).toEqual({ type: 'WOODSMAN', cells: ['MK'] })
  })
})
