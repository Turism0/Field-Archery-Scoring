// Exporting rounds (2.7.03, desktop): two CSV layouts and JSON. Pure functions, tested in test/export.test.ts.
import { AGE_NAMES, CLASS_NAMES, ROUND_TYPE_NAMES, bowName, roundStats, roundToJson, statsTargetCount, statsTotal, type Round } from './round'
import { HIT_NAMES, TARGET_TYPE_NAMES, describeAnimal, describeArrows, isComplete, maxPoints, totalPoints, type TargetScore } from './scoring'
import { applyMultiplier, handicapEntry, multiplier, net, personalHandicap, type HandicapModel } from './handicap'

export type ExportFormat = 'CSV_ROUNDS' | 'CSV_TARGETS' | 'JSON'

/** One CSV field: quoted when it holds a comma, quote or line break (RFC 4180). */
export function csvField(v: unknown): string {
  if (v == null) return ''
  const s = typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(2)) : String(v)
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** CSV text with Windows line ends, so Excel opens it as it is. */
export function toCsv(header: string[], rows: unknown[][]): string {
  return [header, ...rows].map(r => r.map(csvField).join(',')).join('\r\n') + '\r\n'
}

const pct = (v: number | null) => (v == null ? null : Math.round(v * 1000) / 10)

export const ROUND_HEADER = [
  'Date', 'Round type', 'Targets', 'Targets scored', 'Starting target', 'Location', 'Bow', 'Bow code', 'Class', 'Age group',
  'Total', 'Fun shot points', 'Max for targets scored', 'Average per animal', 'Inner kills', 'Kills', 'Wounds', 'Misses',
  'Hits on 1st arrow', 'Hits on 2nd arrow', 'Hits on 3rd arrow', 'Overridden targets', '1st arrow %', 'Kill %', 'Miss %',
  'Handicap', 'Net score', 'Multiplier', 'Score × multiplier', 'Group round', 'Round id',
]

/**
 * One row per round: the details and its stats (as on the scorecard), and (2.9) the personal handicap before it with
 * the net score, worked out from [all] your rounds, and the class multiplier.
 */
export function roundsCsv(rounds: Round[], model?: HandicapModel | null, all: Round[] = rounds): string {
  const entries = all.map(handicapEntry).filter((e): e is NonNullable<typeof e> => !!e)
  return toCsv(ROUND_HEADER, rounds.map(r => {
    const s = roundStats(r)
    const big = r.type === 'BIG_GAME'
    // Fun shots (3.1): in the total, left out of everything else.
    const pts = statsTotal(r), count = statsTargetCount(r)
    const h = big && count > 0 ? personalHandicap(entries, r.bowStyle, { shootDate: r.shootDate, order: r.createdUtc, bowStyle: r.bowStyle, points: pts, targetCount: count }) : null
    const mult = big && model ? multiplier(model, { bowStyle: r.bowStyle, archerClass: r.archerClass, ageGroup: r.ageGroup }) : null
    const max = r.targets.filter(isComplete).reduce((t, x) => t + maxPoints(x), 0)
    return [
      r.shootDate, ROUND_TYPE_NAMES[r.type], r.targetCount, s.targetsScored, r.startTarget ?? null, r.locationName ?? null,
      bowName(r.bowStyle), r.bowStyle, CLASS_NAMES[r.archerClass], AGE_NAMES[r.ageGroup],
      s.total, s.funPoints || null, max, s.averagePerAnimal, r.type === 'WOODSMAN' ? null : s.innerKills, s.kills, s.wounds, s.misses,
      s.byArrow[0], s.byArrow[1], s.byArrow[2], s.overrides, pct(s.firstArrowRate), pct(s.killRate), pct(s.missRate),
      h?.handicap ?? null, h ? net(pts, count, h.handicap) : null, mult, mult != null ? applyMultiplier(pts, mult) : null,
      r.groupId ? 'Yes' : 'No', r.id,
    ]
  }))
}

export const TARGET_HEADER = [
  'Date', 'Round type', 'Location', 'Bow code', 'Class', 'Age group', 'Round total',
  'Target', 'Target type', 'Animals', 'Points', 'Max', 'Overridden', 'Arrow', 'Hit', 'Result', 'Round id',
]

/** The arrow and hit for a target with one result (a standard target, or Woodsman); blank otherwise. */
function arrowAndHit(t: TargetScore): [number | null, string | null] {
  if (t.overridePoints != null) return [null, null]
  if (t.type === 'WOODSMAN') {
    if (!t.arrows) return [null, null]
    const k = t.arrows.indexOf('K')
    return k >= 0 ? [k + 1, 'Kill'] : t.arrows.includes('W') ? [t.arrows.lastIndexOf('W') + 1, 'Wound'] : [null, 'Miss']
  }
  if (t.animals.length !== 1) return [null, null]
  const a = t.animals[0]
  return a.state === 'SCORED' ? [a.arrowNo, HIT_NAMES[a.hit]] : a.state === 'MISSED' ? [null, 'Miss'] : [null, null]
}

function result(t: TargetScore): string {
  if (t.type === 'FUN') return t.overridePoints != null ? `Fun shot: ${t.overridePoints} (not in stats)` : 'Fun shot: not scored'
  if (t.overridePoints != null) return `Overridden: ${t.overridePoints}`
  if (t.type === 'WOODSMAN') return describeArrows(t.arrows)
  return t.animals.map(describeAnimal).join('; ')
}

/** One row per target, in target order, with the round's details repeated (for pivoting in Excel or Power BI). */
export function targetsCsv(rounds: Round[]): string {
  const rows: unknown[][] = []
  for (const r of rounds) {
    const total = roundStats(r).total
    for (const t of [...r.targets].sort((a, b) => a.targetNo - b.targetNo)) {
      const [arrow, hit] = arrowAndHit(t)
      rows.push([
        r.shootDate, ROUND_TYPE_NAMES[r.type], r.locationName ?? null, r.bowStyle, CLASS_NAMES[r.archerClass], AGE_NAMES[r.ageGroup], total,
        t.targetNo, TARGET_TYPE_NAMES[t.type], t.type === 'WOODSMAN' ? 1 : t.animals.length,
        isComplete(t) ? totalPoints(t) : null, maxPoints(t), t.overridePoints != null ? 'Yes' : 'No', arrow, hit, result(t), r.id,
      ])
    }
  }
  return toCsv(TARGET_HEADER, rows)
}

/** The rounds in the API's round JSON (server/README.md), plus the id, location name and bow name. */
export function roundsJson(rounds: Round[], exportedUtc: string, appVersion: string): string {
  return JSON.stringify({
    exportedUtc, app: `Field Archery Scoring web app ${appVersion}`, rounds: rounds.map(r => ({
      roundId: r.id, ...roundToJson(r), locationName: r.locationName ?? null, bowName: bowName(r.bowStyle),
    })),
  }, null, 2)
}

/** The file to save: name, type and text. Excel needs the BOM to read UTF-8 (e.g. é in a location). */
export function exportFile(format: ExportFormat, rounds: Round[], opts: { today: string; nowUtc: string; appVersion: string; model?: HandicapModel | null; all?: Round[] }) {
  const one = rounds.length === 1 ? `round-${rounds[0].shootDate}` : `rounds-${opts.today}`
  if (format === 'JSON') return { name: `field-archery-${one}.json`, type: 'application/json', text: roundsJson(rounds, opts.nowUtc, opts.appVersion) }
  const text = '﻿' + (format === 'CSV_ROUNDS' ? roundsCsv(rounds, opts.model, opts.all ?? rounds) : targetsCsv(rounds))
  return { name: `field-archery-${one}${format === 'CSV_TARGETS' ? '-targets' : ''}.csv`, type: 'text/csv;charset=utf-8', text }
}
