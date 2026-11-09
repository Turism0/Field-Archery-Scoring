// The handicap (as the app's Handicap.kt; model fitted on the server, see docs/HANDICAP.md). Turns a score with any
// bow and class into the same performance for another, and into handicap points: out of 1000, the same for the same
// performance whatever the bow or class (measured from the middle of all bow styles and classes).
import { BOW_STYLES, type AgeGroup, type ArcherClass, type Round } from './round'
import { isComplete, maxPoints, totalPoints } from './scoring'

export interface HandicapModel {
  modelId: number
  dataThroughYear: number
  yearZero: number
  /** "BOW:LB", "SLOPE:LB", "BOW3D:LB", "CLASS:F-ADULT", "BOWSEX:LB" → value */
  factors: Record<string, number>
}
export interface HArcher { bowStyle: string; archerClass: ArcherClass; ageGroup: AgeGroup }

export const RATING_ROUNDS = 10
export const RATING_MIN_ROUNDS = 3
const AGES: AgeGroup[] = ['ADULT', 'JUNIOR', 'CUB']
const CLASSES: ArcherClass[] = ['GENTS', 'LADIES']
export const REFERENCE: HArcher = { bowStyle: 'UL', archerClass: 'GENTS', ageGroup: 'ADULT' }

export const classCode = (c: ArcherClass, a: AgeGroup) => `${c === 'LADIES' ? 'F' : 'M'}-${a}`

export function modelFromJson(j: any): HandicapModel {
  const factors: Record<string, number> = {}
  for (const f of j.factors ?? []) factors[`${f.kind}:${f.code}`] = Number(f.value)
  return { modelId: j.modelId, dataThroughYear: j.dataThroughYear, yearZero: j.yearZero ?? 2014, factors }
}

const f = (m: HandicapModel, kind: string, code: string) => m.factors[`${kind}:${code}`] ?? 0

export function offset(m: HandicapModel, a: HArcher, year: number): number {
  const y = Math.min(Math.max(year, 2002), m.dataThroughYear)
  const code = a.bowStyle
  return f(m, 'BOW', code) + f(m, 'CLASS', classCode(a.archerClass, a.ageGroup)) + (a.archerClass === 'LADIES' ? f(m, 'BOWSEX', code) : 0) +
    f(m, 'SLOPE', code) * (y - m.yearZero) / 10 + 0.5 * f(m, 'BOW3D', code)
}

const logit = (points: number, max: number) => { const p = Math.min(Math.max(points / max, 0.01), 0.99); return Math.log(p / (1 - p)) }

export function convert(m: HandicapModel, points: number, max: number, from: HArcher, year: number, to: HArcher = REFERENCE): number {
  if (max <= 0) return 0
  const z = logit(points, max) - offset(m, from, year) + offset(m, to, year)
  return max / (1 + Math.exp(-z))
}

export function neutralOffset(m: HandicapModel, year: number): number {
  const all = BOW_STYLES.flatMap(b => CLASSES.flatMap(c => AGES.map(a => offset(m, { bowStyle: b.code, archerClass: c, ageGroup: a }, year))))
    .sort((a, b) => a - b)
  return (all[(all.length - 1) >> 1] + all[all.length >> 1]) / 2
}

export function handicapPoints(m: HandicapModel, points: number, max: number, from: HArcher, year: number): number {
  if (max <= 0) return 0
  const z = logit(points, max) - offset(m, from, year) + neutralOffset(m, year)
  return 1000 / (1 + Math.exp(-z))
}

export interface HandicapRound { shootDate: string; points: number; maxPoints: number; archer: HArcher }

/** A Big Game round for the handicap (fully scored targets only); null for Woodsman or nothing scored. */
export function handicapRound(r: Round): HandicapRound | null {
  if (r.type !== 'BIG_GAME') return null
  const done = r.targets.filter(isComplete)
  const max = done.reduce((s, t) => s + maxPoints(t), 0)
  if (!max) return null
  return { shootDate: r.shootDate, points: done.reduce((s, t) => s + totalPoints(t), 0), maxPoints: max,
    archer: { bowStyle: r.bowStyle, archerClass: r.archerClass, ageGroup: r.ageGroup } }
}

export const roundPoints = (m: HandicapModel, h: HandicapRound) =>
  Math.round(handicapPoints(m, h.points, h.maxPoints, h.archer, Number(h.shootDate.slice(0, 4))))

export interface HandicapRating { rating: number; rounds: number }

export function rating(m: HandicapModel, rounds: HandicapRound[]): HandicapRating | null {
  const latest = rounds.filter(r => r.maxPoints > 0).sort((a, b) => b.shootDate.localeCompare(a.shootDate)).slice(0, RATING_ROUNDS)
  if (latest.length < RATING_MIN_ROUNDS) return null
  const avg = latest.reduce((s, h) => s + handicapPoints(m, h.points, h.maxPoints, h.archer, Number(h.shootDate.slice(0, 4))), 0) / latest.length
  return { rating: Math.round(avg), rounds: latest.length }
}
