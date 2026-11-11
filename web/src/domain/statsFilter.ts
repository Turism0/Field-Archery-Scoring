// The Stats screen's filters (3.2, Pete): round types, bow styles and a date range, the same as the app's
// domain/StatsFilter.kt. Pure functions, tested in test/statsFilter.test.ts.
import { BOW_STYLES, type Round } from './round'
import type { RoundType } from './scoring'

/** ALL time; the last N CALENDAR years including this one (1 = this year so far); or the last N years ROLLING to today. */
export type PeriodKind = 'ALL' | 'CALENDAR' | 'ROLLING'
export interface StatsPeriod { kind: PeriodKind; years: number }
export interface StatsFilter { types: RoundType[]; bows: string[]; period: StatsPeriod }

/** The numbers of years offered. */
export const PERIOD_YEARS = [1, 2, 3, 5, 10]
export const DEFAULT_PERIOD: StatsPeriod = { kind: 'ROLLING', years: 1 }
const TYPE_ORDER: RoundType[] = ['BIG_GAME', 'WOODSMAN']
const bowIndex = (b: string) => { const i = BOW_STYLES.findIndex(x => x.code === b); return i < 0 ? 99 : i }

/** Every period offered, in menu order. */
export function periodOptions(): StatsPeriod[] {
  return [
    ...PERIOD_YEARS.map(years => ({ kind: 'ROLLING' as const, years })),
    ...PERIOD_YEARS.map(years => ({ kind: 'CALENDAR' as const, years })),
    { kind: 'ALL', years: 0 },
  ]
}

/** e.g. "Last 1 year (rolling)", "This year (2026)", "Last 3 years (2024–2026)", "All time". */
export function periodLabel(p: StatsPeriod, today: string): string {
  const year = Number(today.slice(0, 4))
  if (p.kind === 'ALL') return 'All time'
  if (p.kind === 'ROLLING') return `Last ${p.years} year${p.years === 1 ? '' : 's'} (rolling)`
  return p.years === 1 ? `This year (${year})` : `Last ${p.years} years (${year - p.years + 1}–${year})`
}

export const samePeriod = (a: StatsPeriod, b: StatsPeriod) => a.kind === b.kind && (a.kind === 'ALL' || a.years === b.years)

/** [today] minus [years] years, as YYYY-MM-DD (29 Feb → 28 Feb). */
function yearsBefore(today: string, years: number): string {
  const y = Number(today.slice(0, 4)) - years
  const md = today.slice(5) === '02-29' ? '02-28' : today.slice(5)
  return `${String(y).padStart(4, '0')}-${md}`
}

export function inStatsPeriod(p: StatsPeriod, shootDate: string, today: string): boolean {
  if (p.kind === 'ALL') return true
  if (p.kind === 'CALENDAR') return Number(shootDate.slice(0, 4)) >= Number(today.slice(0, 4)) - p.years + 1
  return shootDate > yearsBefore(today, p.years)   // rolling: after the same date [years] years ago
}

export const matchesFilter = (f: StatsFilter, r: Round, today: string) =>
  f.types.includes(r.type) && f.bows.includes(r.bowStyle) && inStatsPeriod(f.period, r.shootDate, today)

/** Round types and bow styles you have finished rounds with (the only ones offered). */
export function availableTypes(rounds: Round[]): RoundType[] {
  return TYPE_ORDER.filter(t => rounds.some(r => r.type === t))
}
export function availableBows(rounds: Round[]): string[] {
  return [...new Set(rounds.map(r => r.bowStyle))].sort((a, b) => bowIndex(a) - bowIndex(b))
}

/**
 * The filter to use: what was chosen last time ([saved]), less anything you no longer have rounds for. The first time
 * (or when nothing saved is left): every round type, your profile's bow style if you've shot with it (else the one
 * you've shot most), and the last year rolling.
 */
export function resolveFilter(saved: Partial<StatsFilter> | null, rounds: Round[], profileBow: string | null | undefined): StatsFilter {
  const types = availableTypes(rounds), bows = availableBows(rounds)
  const keptTypes = (saved?.types ?? []).filter(t => types.includes(t))
  const keptBows = (saved?.bows ?? []).filter(b => bows.includes(b))
  let defaultBow: string | undefined = profileBow && bows.includes(profileBow) ? profileBow : undefined
  if (!defaultBow) {
    const count = new Map<string, number>()
    for (const r of rounds) count.set(r.bowStyle, (count.get(r.bowStyle) ?? 0) + 1)
    defaultBow = [...count.entries()].sort((a, b) => b[1] - a[1] || bowIndex(a[0]) - bowIndex(b[0]))[0]?.[0]
  }
  const period = saved?.period && periodOptions().some(p => samePeriod(p, saved.period!)) ? saved.period : DEFAULT_PERIOD
  return {
    types: keptTypes.length ? keptTypes : types,
    bows: keptBows.length ? keptBows : defaultBow ? [defaultBow] : [],
    period,
  }
}

/** Toggles one value in a multiple choice, never leaving it empty. */
export function toggled<T>(list: T[], value: T, order: (x: T) => number): T[] {
  if (list.includes(value)) return list.length > 1 ? list.filter(x => x !== value) : list
  return [...list, value].sort((a, b) => order(a) - order(b))
}
export const typeOrder = (t: RoundType) => TYPE_ORDER.indexOf(t)
export { bowIndex as bowOrderOf }
