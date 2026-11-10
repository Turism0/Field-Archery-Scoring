// Handicaps (as the app's Handicap.kt, 2.9; model fitted on the server, see docs/HANDICAP.md): multipliers for a
// class / bow style competition (the maximum ÷ that bow and class's typical score), the personal handicap (like golf,
// par 960 off 40 targets), and turning a score with one bow and class into the same performance for another.
import { type AgeGroup, type ArcherClass, type Round } from './round'
import { isComplete, totalPoints } from './scoring'

export interface HandicapModel {
  modelId: number
  dataThroughYear: number
  yearZero: number
  /** "BOW:LB", "SLOPE:LB", "BOW3D:LB", "CLASS:F-ADULT", "BOWSEX:LB", "BASE:ALL" (2.9) → value */
  factors: Record<string, number>
}
export interface HArcher { bowStyle: string; archerClass: ArcherClass; ageGroup: AgeGroup }

/** The class columns of the tables (multipliers, calculator). */
export const CLASS_COLUMNS: [string, ArcherClass, AgeGroup][] = [
  ['Gents', 'GENTS', 'ADULT'], ['Ladies', 'LADIES', 'ADULT'], ['Jr Boys', 'GENTS', 'JUNIOR'], ['Jr Girls', 'LADIES', 'JUNIOR'],
  ['U12 Boys', 'GENTS', 'CUB'], ['U12 Girls', 'LADIES', 'CUB'],
]
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

/** The level of a typical championship (server 2.9); undefined from older servers. */
export const base = (m: HandicapModel): number | undefined => m.factors['BASE:ALL']

/** A typical score for this bow style and class as a share of the maximum, or null without the base level. */
export function typicalShare(m: HandicapModel, a: HArcher, year = m.dataThroughYear): number | null {
  const b = base(m)
  return b === undefined ? null : 1 / (1 + Math.exp(-(b + offset(m, a, year))))
}

/** Competition multiplier (2.9, Pete): the maximum ÷ a typical score, to 2 decimals (score × this). */
export function multiplier(m: HandicapModel, a: HArcher, year = m.dataThroughYear): number | null {
  const share = typicalShare(m, a, year)
  return share == null ? null : Math.round(100 / share) / 100
}
export const applyMultiplier = (points: number, mult: number) => Math.round(points * mult)

// ---- Personal handicap (2.9, Pete: like golf, par = the maximum off a 40-target course). As the app's PersonalHandicap. ----

export const PAR = 960, PAR_TARGETS = 40, MAX_ROUNDS = 20, MIN_ROUNDS = 3

/** One finished, fully scored Big Game round. order: tells apart rounds on the same day (when it was started). */
export interface HEntry { shootDate: string; order: string; bowStyle: string; points: number; targetCount: number }
export interface PersonalH { bowStyle: string; handicap: number; usual: number; counted: HEntry[]; considered: number }

export const scaled = (e: HEntry) => e.points * PAR_TARGETS / e.targetCount
/** How many of n recent rounds count: 40% (8 of 20), at least 3; 0 when there are too few. */
export const counting = (n: number) => (n < MIN_ROUNDS ? 0 : Math.min(n, Math.max(MIN_ROUNDS, Math.round(n * 0.4))))
const isBefore = (a: HEntry, b: HEntry) => a.shootDate < b.shootDate || (a.shootDate === b.shootDate && a.order < b.order)

/** The handicap with this bow; with before, only rounds shot before that one (the handicap you had then). */
export function personalHandicap(entries: HEntry[], bow: string, before?: HEntry): PersonalH | null {
  const recent = entries.filter(e => e.bowStyle === bow && e.targetCount > 0 && (!before || isBefore(e, before)))
    .sort((a, b) => b.shootDate.localeCompare(a.shootDate) || b.order.localeCompare(a.order)).slice(0, MAX_ROUNDS)
  const k = counting(recent.length)
  if (!k) return null
  const best = [...recent].sort((a, b) => scaled(b) - scaled(a)).slice(0, k)
  const usual = best.reduce((x, e) => x + scaled(e), 0) / k
  return { bowStyle: bow, handicap: Math.round(PAR - usual), usual, counted: best, considered: recent.length }
}

/** A handicap for every bow style with enough rounds, most rounds first. */
export function personalHandicaps(entries: HEntry[]): PersonalH[] {
  const counts = new Map<string, number>()
  entries.forEach(e => counts.set(e.bowStyle, (counts.get(e.bowStyle) ?? 0) + 1))
  return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([bow]) => personalHandicap(entries, bow)).filter((h): h is PersonalH => !!h)
}

export const allowance = (handicap: number, targetCount: number) => Math.round(handicap * targetCount / PAR_TARGETS)
export const net = (points: number, targetCount: number, handicap: number) => points + allowance(handicap, targetCount)
export const parFor = (targetCount: number) => 24 * targetCount

/** Your own finished, fully scored Big Game round for the personal handicap; null otherwise. */
export function handicapEntry(r: Round): HEntry | null {
  if (r.type !== 'BIG_GAME' || r.status !== 'COMPLETED' || r.targetCount <= 0) return null
  if (r.targets.length !== r.targetCount || !r.targets.every(isComplete)) return null
  return { shootDate: r.shootDate, order: r.createdUtc, bowStyle: r.bowStyle, points: r.targets.reduce((x, t) => x + totalPoints(t), 0), targetCount: r.targetCount }
}
