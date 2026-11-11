// Typing in a paper scorecard (desktop, 2.6.02): one box per animal holding its points.
// Big Game points belong to exactly one arrow and hit (24 inner kill, 20 kill, 16 wound on arrow 1; 14 / 10 on
// arrow 2; 8 / 4 on arrow 3; 0 miss), so the details and stats come out the same as scoring hit by hit.
// Woodsman takes the arrows as letters (W wound, K kill, M miss, e.g. "MK"): its points aren't unique (20 is MK or WWK).
// "=37" (or a plain number on Woodsman) is a target entered by hand: the points only.
// A fun shot (3.1) takes its score as a plain number (0 to 999): in the total, not in stats.
import {
  FUN_MAX, MAX_FAN, MIN_FAN, PENDING, arrowsAvailable, bigGamePoints, newTarget, withAnimalResult, withOverride, withType, woodsmanValid,
  type AnimalResult, type Hit, type RoundType, type TargetScore, type TargetType,
} from './scoring'

export const POINTS: Record<number, { arrowNo: number; hit: Hit }> = {
  24: { arrowNo: 1, hit: 'INNER_KILL' }, 20: { arrowNo: 1, hit: 'KILL' }, 16: { arrowNo: 1, hit: 'WOUND' },
  14: { arrowNo: 2, hit: 'KILL' }, 10: { arrowNo: 2, hit: 'WOUND' }, 8: { arrowNo: 3, hit: 'KILL' }, 4: { arrowNo: 3, hit: 'WOUND' },
}

/** One target as typed: its kind (Big Game), and a box per animal (Woodsman: one box). */
export interface RowEntry { type: TargetType; cells: string[] }

export function emptyRow(roundType: RoundType): RowEntry {
  return roundType === 'WOODSMAN' ? { type: 'WOODSMAN', cells: [''] } : { type: 'STANDARD', cells: [''] }
}

/** A row's kind changed (Big Game): keep what was typed in the boxes that remain. */
export function withRowType(row: RowEntry, type: TargetType, fanAnimals = MIN_FAN): RowEntry {
  const n = type === 'PREDATOR_PREY' ? 2 : type === 'FAN' ? Math.min(Math.max(fanAnimals, MIN_FAN), MAX_FAN) : 1
  return { type, cells: Array.from({ length: n }, (_, i) => row.cells[i] ?? '') }
}

const byHand = (text: string) => /^=\s*\d{1,3}$/.test(text.trim()) ? Number(text.trim().slice(1)) : null

/** The target for a row, or a message saying what's wrong. Blank boxes are just not scored yet. */
export function rowToTarget(roundType: RoundType, targetNo: number, row: RowEntry): { target: TargetScore } | { error: string } {
  let t = newTarget(roundType, targetNo)
  if (roundType === 'WOODSMAN') {
    const text = row.cells[0].trim().toUpperCase()
    if (!text) return { target: t }
    if (/^=?\d{1,2}$/.test(text)) {
      const n = Number(text.replace('=', ''))
      return n <= 30 ? { target: withOverride(t, n) } : { error: 'A Woodsman target is 30 at most.' }
    }
    if (!woodsmanValid(text)) return { error: 'Woodsman: up to 3 arrows as W, K or M (nothing after a K), e.g. WK.' }
    return { target: { ...t, arrows: text } }
  }
  if (row.type === 'FUN') {
    t = withType(t, 'FUN')
    const text = (row.cells[0] ?? '').trim().replace(/^=\s*/, '')
    if (!text) return { target: t }
    if (!/^\d{1,3}$/.test(text) || Number(text) > FUN_MAX) return { error: `A fun shot's score is a whole number, 0 to ${FUN_MAX}.` }
    return { target: withOverride(t, Number(text)) }
  }
  const first = byHand(row.cells[0] ?? '')
  t = row.type === 'STANDARD' ? t : withType(t, row.type, row.cells.length)
  if (first != null) {
    const max = t.animals.length * 24
    return first <= max ? { target: withOverride(t, first) } : { error: `${first} is more than the most for this target (${max}).` }
  }
  for (let i = 0; i < row.cells.length; i++) {
    const text = row.cells[i].trim()
    if (!text) continue
    if (!/^\d{1,2}$/.test(text)) return { error: `"${text}" isn't a Big Game score. Use 24, 20, 16, 14, 10, 8, 4 or 0 (or =N for a total by hand).` }
    const n = Number(text)
    let result: AnimalResult
    if (n === 0) result = { state: 'MISSED' }
    else if (POINTS[n]) result = { state: 'SCORED', ...POINTS[n] }
    else return { error: `${n} isn't a Big Game score. Use 24, 20, 16, 14, 10, 8, 4 or 0.` }
    if (result.state === 'SCORED' && result.arrowNo > arrowsAvailable(t.type, t.animals, i)) {
      return { error: `The prey only has the arrows the predator left: ${n} needs arrow ${result.arrowNo}.` }
    }
    if (result.state === 'MISSED' && i > 0 && t.type === 'PREDATOR_PREY' && arrowsAvailable(t.type, t.animals, i) === 0) continue
    t = withAnimalResult(t, i, result)
  }
  return { target: t }
}

/** What a scored target looks like typed in (for editing a round). */
export function targetToRow(t: TargetScore): RowEntry {
  if (t.type === 'WOODSMAN') return { type: 'WOODSMAN', cells: [t.overridePoints != null ? String(t.overridePoints) : t.arrows] }
  if (t.type === 'FUN') return { type: 'FUN', cells: [t.overridePoints != null ? String(t.overridePoints) : ''] }
  if (t.overridePoints != null) return { type: t.type, cells: t.animals.map((_, i) => (i === 0 ? `=${t.overridePoints}` : '')) }
  return { type: t.type, cells: t.animals.map(a => a.state === 'SCORED' ? String(bigGamePoints(a.arrowNo, a.hit)) : a.state === 'MISSED' ? '0' : '') }
}

/** Boxes that can't be used: the prey when the predator used every arrow (or missed). */
export function cellDisabled(row: RowEntry, roundType: RoundType, i: number): boolean {
  if (roundType === 'WOODSMAN' || row.type !== 'PREDATOR_PREY' || i === 0) return false
  if (byHand(row.cells[0] ?? '') != null) return true
  const p = row.cells[0]?.trim()
  if (!p) return false
  const n = Number(p)
  return n === 0 || (POINTS[n] ? POINTS[n].arrowNo >= 3 : false)
}

export { PENDING }
