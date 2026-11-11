// The target a round started on (2.7): kept with the round and in its JSON. 3.1.03: any target 1 to 999, not just up to
// the number shot (an 18-target half can start at 19).
import { describe, expect, it } from 'vitest'
import { newRound, roundFromJson, roundToJson } from '../src/domain/round'

const base = { shootDate: '2026-11-09', targetCount: 36, archerClass: 'GENTS' as const, ageGroup: 'ADULT' as const, bowStyle: 'LB', type: 'BIG_GAME' as const }

describe('start target', () => {
  it('goes to and comes back from the server', () => {
    const r = newRound({ ...base, startTarget: 12 })
    const j = roundToJson(r)
    expect(j.startTarget).toBe(12)
    expect(roundFromJson({ ...j, roundId: r.id }).startTarget).toBe(12)
  })
  it('is optional, and only a target on the round', () => {
    expect(newRound(base).startTarget).toBeNull()
    expect(newRound({ ...base, startTarget: 37 }).startTarget).toBe(37)
    expect(newRound({ ...base, startTarget: 999 }).startTarget).toBe(999)
    expect(newRound({ ...base, startTarget: 1000 }).startTarget).toBeNull()
    expect(newRound({ ...base, startTarget: 0 }).startTarget).toBeNull()
    expect(roundToJson(newRound(base)).startTarget).toBeNull()
    expect(roundFromJson({ ...roundToJson(newRound(base)), roundId: 'x' }).startTarget).toBeNull()
  })
})
