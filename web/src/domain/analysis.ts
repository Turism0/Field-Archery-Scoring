// Stats analysis (3.2.17, Pete): how you score (arrows, hits, target types), consistency and form, start and finish,
// by location and by weather, from your own finished rounds. Pure functions, the same as the app's domain/Analysis.kt.
// Tested in test/analysis.test.ts. Averages are per animal (Big Game, out of 24) or per target (Woodsman, out of 30);
// fun shots (3.1) are left out of everything.
import { isFun, isComplete, totalPoints, type TargetScore, type RoundType } from './scoring'
import { roundStats, target, type Round } from './round'
import { shotOrder } from './stats'
import { isCold, isWet, isWindy, type Weather } from './weather'

/** Animals (Woodsman: targets) whose arrows were recorded: fully scored, not overridden, not fun shots. */
function recordedTargets(r: Round): TargetScore[] {
  return r.targets.filter(t => !isFun(t) && isComplete(t) && t.overridePoints == null)
}

// ---- 1. Arrow analysis ----

/** How many animals (Woodsman: targets) were hit, or killed for Woodsman, on arrow 1, 2 or 3, and how many not at all. */
export interface ArrowCounts { first: number; second: number; third: number; none: number; total: number }

export function arrowCounts(rounds: Round[]): ArrowCounts {
  const c = { first: 0, second: 0, third: 0, none: 0, total: 0 }
  const add = (arrow: number | null) => {
    c.total++
    if (arrow === 1) c.first++; else if (arrow === 2) c.second++; else if (arrow === 3) c.third++; else c.none++
  }
  for (const r of rounds) for (const t of recordedTargets(r)) {
    if (r.type === 'WOODSMAN') { const k = t.arrows.indexOf('K'); add(k < 0 ? null : k + 1) }
    else for (const a of t.animals) add(a.state === 'SCORED' ? a.arrowNo : null)
  }
  return c
}

// ---- 2. Hit breakdown ----

/**
 * Big Game: the scoring hit on each recorded animal (inner kill, kill, wound) or a miss. Woodsman (per target): killed,
 * wounded only, or nothing (innerKill is always 0).
 */
export interface HitCounts { innerKill: number; kill: number; wound: number; miss: number; total: number }

export function hitCounts(rounds: Round[]): HitCounts {
  const c = { innerKill: 0, kill: 0, wound: 0, miss: 0, total: 0 }
  for (const r of rounds) for (const t of recordedTargets(r)) {
    if (r.type === 'WOODSMAN') {
      c.total++
      if (t.arrows.includes('K')) c.kill++; else if (t.arrows.includes('W')) c.wound++; else c.miss++
    } else for (const a of t.animals) {
      c.total++
      if (a.state !== 'SCORED') c.miss++
      else if (a.hit === 'INNER_KILL') c.innerKill++
      else if (a.hit === 'KILL') c.kill++
      else c.wound++
    }
  }
  return c
}

/** Hits for your most recent [n] rounds (newest by shoot date). */
export const recentRounds = (rounds: Round[], n: number) => [...rounds].sort(shotOrder).slice(-n)

export interface HitPeriod { label: string; hits: HitCounts }

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/**
 * Hits over time, oldest first: by year when the rounds cover two or more years, otherwise by month. At most the last
 * [max] periods with rounds.
 */
export function hitsOverTime(rounds: Round[], max = 8): HitPeriod[] {
  const years = new Set(rounds.map(r => r.shootDate.slice(0, 4)))
  const byYear = years.size >= 2
  const key = (r: Round) => (byYear ? r.shootDate.slice(0, 4) : r.shootDate.slice(0, 7))
  const keys = [...new Set(rounds.map(key))].sort().slice(-max)
  return keys.map(k => ({
    label: byYear ? k : `${MONTHS[Number(k.slice(5, 7)) - 1]} ${k.slice(0, 4)}`,
    hits: hitCounts(rounds.filter(r => key(r) === k)),
  })).filter(p => p.hits.total > 0)
}

// ---- 3. By target type (Big Game) ----

export interface TypeAverage { type: 'STANDARD' | 'PREDATOR_PREY' | 'FAN'; targets: number; animals: number; average: number }

/** Average per animal on standard targets, predator/prey and fans (fully scored targets, overrides included). */
export function byTargetType(rounds: Round[]): TypeAverage[] {
  const out: TypeAverage[] = []
  for (const type of ['STANDARD', 'PREDATOR_PREY', 'FAN'] as const) {
    const ts = rounds.filter(r => r.type === 'BIG_GAME').flatMap(r => r.targets).filter(t => t.type === type && isComplete(t))
    const animals = ts.reduce((s, t) => s + t.animals.length, 0)
    if (animals) out.push({ type, targets: ts.length, animals, average: ts.reduce((s, t) => s + totalPoints(t), 0) / animals })
  }
  return out
}

// ---- 4 & 5. Consistency, streaks and form ----

/** Each round's average per animal, oldest first (rounds with nothing scored left out). */
export function roundAverages(rounds: Round[]): { round: Round; average: number }[] {
  return [...rounds].sort(shotOrder).map(r => ({ round: r, average: roundStats(r).averagePerAnimal }))
    .filter((x): x is { round: Round; average: number } => x.average != null)
}

/** The average of the round averages and how far rounds usually are from it (standard deviation). Needs 3 rounds. */
export interface Consistency { rounds: number; average: number; spread: number }

export function consistency(rounds: Round[]): Consistency | null {
  const xs = roundAverages(rounds).map(x => x.average)
  if (xs.length < 3) return null
  const mean = xs.reduce((s, x) => s + x, 0) / xs.length
  const spread = Math.sqrt(xs.reduce((s, x) => s + (x - mean) ** 2, 0) / (xs.length - 1))
  return { rounds: xs.length, average: mean, spread }
}

/**
 * Form: your last 5 rounds' average against the rounds before them. UP or DOWN when it's moved by 2% of the maximum
 * per animal (about half a point Big Game) or more, otherwise STEADY. Needs 5 rounds and at least 1 before them.
 */
export interface Form { recent: number; before: number; change: number; direction: 'UP' | 'DOWN' | 'STEADY' }

export function form(rounds: Round[], type: RoundType, n = 5): Form | null {
  const xs = roundAverages(rounds).map(x => x.average)
  if (xs.length < n + 1) return null
  const avg = (l: number[]) => l.reduce((s, x) => s + x, 0) / l.length
  const recent = avg(xs.slice(-n)), before = avg(xs.slice(0, -n)), change = recent - before
  const step = (type === 'WOODSMAN' ? 30 : 24) * 0.02
  return { recent, before, change, direction: change >= step ? 'UP' : change <= -step ? 'DOWN' : 'STEADY' }
}

/** Rounds in a row at or above your average: the run you're on now and the longest. */
export interface Streak { average: number; current: number; best: number }

export function streak(rounds: Round[]): Streak | null {
  const xs = roundAverages(rounds).map(x => x.average)
  if (xs.length < 3) return null
  const mean = xs.reduce((s, x) => s + x, 0) / xs.length
  let current = 0, best = 0
  for (const x of xs) { current = x >= mean ? current + 1 : 0; best = Math.max(best, current) }
  return { average: mean, current, best }
}

// ---- 6. Start, middle and finish ----

/** Target numbers in the order they were shot: from the starting target round to the one before it. */
export function shootingOrder(r: Round): number[] {
  const n = r.targetCount
  const s = r.startTarget != null && r.startTarget >= 1 && r.startTarget <= n ? r.startTarget : 1
  return Array.from({ length: n }, (_, i) => ((s - 1 + i) % n) + 1)
}

/** Average per animal in the first, middle and last third of each round (in shooting order; fun shots left out). */
export interface Thirds { start: number | null; middle: number | null; finish: number | null }

export function thirds(rounds: Round[]): Thirds {
  const pts = [0, 0, 0], animals = [0, 0, 0]
  for (const r of rounds) {
    const order = shootingOrder(r).map(n => target(r, n)).filter(t => !isFun(t))
    order.forEach((t, i) => {
      if (!isComplete(t)) return
      const third = Math.min(2, Math.floor((i * 3) / order.length))
      pts[third] += totalPoints(t)
      animals[third] += r.type === 'WOODSMAN' ? 1 : t.animals.length
    })
  }
  const a = (i: number) => (animals[i] ? pts[i] / animals[i] : null)
  return { start: a(0), middle: a(1), finish: a(2) }
}

// ---- By equipment (3.2.24) ----

export interface EquipmentSummary { id: string; rounds: number; average: number; best: number; bestRound: Round; killRate: number | null }

/** Each bow's (key = r => r.bowId) or arrows' (r => r.arrowId) rounds, average, best and kill rate. Most rounds first. */
export function byEquipment(rounds: Round[], key: (r: Round) => string | null | undefined): EquipmentSummary[] {
  const groups = new Map<string, Round[]>()
  for (const r of rounds) { const k = key(r); if (k) groups.set(k, [...(groups.get(k) ?? []), r]) }
  const out: EquipmentSummary[] = []
  for (const [id, list] of groups) {
    const avgs = roundAverages(list)
    if (!avgs.length) continue
    const best = avgs.reduce((b, x) => (x.average > b.average ? x : b))
    const hits = hitCounts(list.filter(r => r.type === 'BIG_GAME'))
    out.push({ id, rounds: avgs.length, average: avgs.reduce((s, x) => s + x.average, 0) / avgs.length, best: best.average,
      bestRound: best.round, killRate: hits.total ? (hits.innerKill + hits.kill) / hits.total : null })
  }
  return out.sort((a, b) => b.rounds - a.rounds || b.average - a.average || a.id.localeCompare(b.id))
}

// ---- 8. By location ----

export interface LocationSummary { name: string; rounds: number; average: number; best: number; bestRound: Round }

/** Your rounds at each named location: how many, average and best average per animal. Most-shot first. */
export function byLocation(rounds: Round[]): LocationSummary[] {
  const groups = new Map<string, Round[]>()
  for (const r of rounds) {
    const name = r.locationName?.trim()
    if (!name) continue
    const k = name.toLowerCase()
    groups.set(k, [...(groups.get(k) ?? []), r])
  }
  const out: LocationSummary[] = []
  for (const list of groups.values()) {
    const avgs = roundAverages(list)
    if (!avgs.length) continue
    const best = avgs.reduce((b, x) => (x.average > b.average ? x : b))
    out.push({ name: list[0].locationName!.trim(), rounds: avgs.length, average: avgs.reduce((s, x) => s + x.average, 0) / avgs.length,
      best: best.average, bestRound: best.round })
  }
  return out.sort((a, b) => b.rounds - a.rounds || b.average - a.average || a.name.localeCompare(b.name))
}

// ---- 10. Weather ----

/** Two sides of a weather comparison: rounds and average per animal on each. */
export interface WeatherSplit { label: string; yesLabel: string; noLabel: string; yes: { rounds: number; average: number | null }; no: { rounds: number; average: number | null } }

export function weatherSplits(rounds: Round[], weatherOf: (r: Round) => Weather | null | undefined): WeatherSplit[] {
  const withWeather = roundAverages(rounds).map(x => ({ ...x, w: weatherOf(x.round) })).filter(x => x.w)
  const side = (l: typeof withWeather) => ({ rounds: l.length, average: l.length ? l.reduce((s, x) => s + x.average, 0) / l.length : null })
  const split = (label: string, yesLabel: string, noLabel: string, f: (w: Weather) => boolean): WeatherSplit => ({
    label, yesLabel, noLabel, yes: side(withWeather.filter(x => f(x.w!))), no: side(withWeather.filter(x => !f(x.w!))),
  })
  return [
    split('Rain', 'Wet', 'Dry', isWet),
    split('Wind', 'Windy (15+ mph)', 'Calmer', isWindy),
    split('Temperature', 'Cold (under 8°C)', 'Milder', isCold),
  ]
}

// ---- 7. Compared with others (the server gives how many of everyone else's rounds you beat) ----

/** "Better than 70% of Gents Adult Longbow rounds", or null with too few rounds to say. */
export function comparedText(betterThan: number | null, rounds: number, who: string): string | null {
  if (betterThan == null || rounds < 20) return null
  return `Better than ${Math.round(betterThan * 100)}% of ${who} rounds`
}
