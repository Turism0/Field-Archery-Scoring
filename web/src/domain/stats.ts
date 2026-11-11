// History and stats (as the app's PersonalStats.kt): personal bests, trends and season summaries from your own
// finished rounds. Averages are per animal (Big Game, out of 24) or per target (Woodsman, out of 30).
import { BOW_STYLES, isFullyScored, roundStats, statsTargetCount, statsTotal, type Round, type RoundStats } from './round'
import type { RoundType } from './scoring'

export const MIN_TARGETS_FOR_BEST_AVERAGE = 18

const typeOrder = (t: RoundType) => (t === 'BIG_GAME' ? 0 : 1)
export const bowOrder = (code: string) => { const i = BOW_STYLES.findIndex(b => b.code === code); return i < 0 ? 99 : i }

/** Oldest first; same day in the order they were started. */
export function shotOrder(a: Round, b: Round): number {
  return a.shootDate.localeCompare(b.shootDate) || a.createdUtc.localeCompare(b.createdUtc) || a.id.localeCompare(b.id)
}

// Fun shots (3.1) are left out: bests compare the total without them, on the targets without them.
const bestKey = (r: Round) => `${r.type}|${r.bowStyle}|${statsTargetCount(r)}`
const finishedFull = (rounds: Round[]) => rounds.filter(r => r.status === 'COMPLETED' && isFullyScored(r))

function groupBy<T>(list: T[], key: (x: T) => string): Map<string, T[]> {
  const m = new Map<string, T[]>()
  for (const x of list) { const k = key(x); const l = m.get(k); if (l) l.push(x); else m.set(k, [x]) }
  return m
}

/** Highest by f; the first (in the given order) wins a tie. */
function maxBy<T>(list: T[], f: (x: T) => number): T | undefined {
  let best: T | undefined, bv = -Infinity
  for (const x of list) { const v = f(x); if (v > bv) { best = x; bv = v } }
  return best
}

export interface PersonalBest { type: RoundType; bowStyle: string; targetCount: number; best: Round; roundsShot: number; averageTotal: number }
export interface BestAverage { type: RoundType; bowStyle: string; round: Round; average: number }

export function bests(rounds: Round[]): PersonalBest[] {
  return [...groupBy(finishedFull(rounds), bestKey).values()].map(list => {
    const sorted = [...list].sort(shotOrder)
    const r = sorted[0]
    return {
      type: r.type, bowStyle: r.bowStyle, targetCount: statsTargetCount(r), best: maxBy(sorted, statsTotal)!, roundsShot: list.length,
      averageTotal: list.reduce((s, x) => s + statsTotal(x), 0) / list.length,
    }
  }).sort((a, b) => typeOrder(a.type) - typeOrder(b.type) || bowOrder(a.bowStyle) - bowOrder(b.bowStyle) ||
    b.roundsShot - a.roundsShot || a.targetCount - b.targetCount)
}

export function bestAverages(rounds: Round[]): BestAverage[] {
  const list = finishedFull(rounds).filter(r => statsTargetCount(r) >= MIN_TARGETS_FOR_BEST_AVERAGE)
  const out: BestAverage[] = []
  for (const l of groupBy(list, r => `${r.type}|${r.bowStyle}`).values()) {
    const best = maxBy([...l].sort(shotOrder), r => roundStats(r).averagePerAnimal ?? -1)
    const avg = best && roundStats(best).averagePerAnimal
    if (best && avg != null) out.push({ type: best.type, bowStyle: best.bowStyle, round: best, average: avg })
  }
  return out.sort((a, b) => typeOrder(a.type) - typeOrder(b.type) || bowOrder(a.bowStyle) - bowOrder(b.bowStyle))
}

// ---- The PB badge (3.1.08, Pete): only your current best, by average per animal, for each round type and bow style ----

/** What a PB is per: round type and bow style (rounds of any length compared on their average). */
const pbKey = (r: Round) => `${r.type}|${r.bowStyle}`
/** Average per animal (Woodsman: per target), fun shots left out; -1 when there's nothing scored. */
export const pbAverage = (r: Round) => roundStats(r).averagePerAnimal ?? -1

/** The rounds that are your current personal best: the highest average for each round type and bow style (the first shot wins a tie). */
export function personalBestIds(rounds: Round[]): Set<string> {
  const ids = new Set<string>()
  for (const list of groupBy(finishedFull(rounds), pbKey).values()) {
    const best = maxBy([...list].sort(shotOrder), pbAverage)
    if (best && pbAverage(best) >= 0) ids.add(best.id)
  }
  return ids
}

/** The best earlier round of the same type and bow style, by average (what this one had to beat). */
export function previousBest(round: Round, rounds: Round[]): Round | undefined {
  return maxBy(finishedFull(rounds).filter(r => r.id !== round.id && pbKey(r) === pbKey(round) && shotOrder(r, round) < 0).sort(shotOrder), pbAverage)
}

export interface TrendPoint { round: Round; date: string; average: number; rolling: number }

export function trend(rounds: Round[], type: RoundType, bowStyle: string | null, window = 5): TrendPoint[] {
  const list = rounds.filter(r => r.status === 'COMPLETED' && r.type === type && (bowStyle == null || r.bowStyle === bowStyle))
    .sort(shotOrder).map(r => ({ r, avg: roundStats(r).averagePerAnimal })).filter((x): x is { r: Round; avg: number } => x.avg != null)
  return list.map(({ r, avg }, i) => {
    const recent = list.slice(Math.max(0, i - window + 1), i + 1).map(x => x.avg)
    return { round: r, date: r.shootDate, average: avg, rolling: recent.reduce((s, v) => s + v, 0) / recent.length }
  })
}

export interface Season {
  year: number; type: RoundType; rounds: number; targets: number; locations: number; stats: RoundStats
  bestRound: Round | undefined; byBow: { bowStyle: string; rounds: number; average: number | null }[]; personalBests: number
}

/** All these rounds' targets as one long round, so the usual stats give combined figures. */
export function combined(rounds: Round[]): Round {
  const targets = rounds.flatMap(r => r.targets).map((t, i) => ({ ...t, targetNo: i + 1 }))
  return { ...rounds[0], id: 'combined', targetCount: targets.length, targets }
}

export function seasons(rounds: Round[]): Season[] {
  const finished = rounds.filter(r => r.status === 'COMPLETED')
  const pbs = personalBestIds(finished)
  return [...groupBy(finished, r => `${r.shootDate.slice(0, 4)}|${r.type}`).values()].map(list => {
    const byBow = [...groupBy(list, r => r.bowStyle).entries()].sort((a, b) => bowOrder(a[0]) - bowOrder(b[0]))
      .map(([bow, l]) => ({ bowStyle: bow, rounds: l.length, average: roundStats(combined(l)).averagePerAnimal }))
    return {
      year: Number(list[0].shootDate.slice(0, 4)), type: list[0].type, rounds: list.length,
      targets: list.reduce((s, r) => s + roundStats(r).targetsScored, 0),
      locations: new Set(list.map(r => r.locationName?.trim().toLowerCase()).filter(Boolean)).size,
      stats: roundStats(combined(list)), bestRound: maxBy([...list].sort(shotOrder), statsTotal), byBow,
      personalBests: list.filter(r => pbs.has(r.id)).length,
    }
  }).sort((a, b) => b.year - a.year || typeOrder(a.type) - typeOrder(b.type))
}

// ---- history filters ----
export type Period = { kind: 'all' } | { kind: '12m' } | { kind: 'year'; year: number } | { kind: 'between'; from?: string; to?: string }

export function inPeriod(p: Period, date: string, today: string): boolean {
  switch (p.kind) {
    case 'all': return true
    case '12m': {
      const y = Number(today.slice(0, 4)) - 1
      return date > `${y}${today.slice(4)}` && date <= today
    }
    case 'year': return date.startsWith(String(p.year))
    case 'between': return (!p.from || date >= p.from) && (!p.to || date <= p.to)
  }
}

export const periodLabel = (p: Period) =>
  p.kind === 'all' ? 'All time' : p.kind === '12m' ? 'Last 12 months' : p.kind === 'year' ? String(p.year) : 'Dates…'

export const years = (dates: string[]) => [...new Set(dates.map(d => Number(d.slice(0, 4))))].sort((a, b) => b - a)
