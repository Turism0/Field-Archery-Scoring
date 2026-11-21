// What the scoring screen shows about a card (3.2.20, Pete): knowing how you're doing can affect your shooting, so
// each figure can be switched off, separately for your card and other archers' cards in a group. Shown at the top of
// the scoring screen, in the ☰ scorecard sheet and (the total) in the group names strip. Same as the app's
// domain/ScoringDisplay.kt. Tested in test/scoringDisplay.test.ts.

export interface StatsDisplay {
  total: boolean
  average: boolean
  /** Inner kill / kill / wound / miss counts (and which arrow scored, in the scorecard sheet). */
  hits: boolean
  remaining: boolean
  maxPossible: boolean
}
export interface ScoringDisplay { mine: StatsDisplay; others: StatsDisplay }

export const DEFAULT_STATS_DISPLAY: StatsDisplay = { total: true, average: true, hits: true, remaining: false, maxPossible: false }
const KEYS = ['total', 'average', 'hits', 'remaining', 'maxPossible'] as const

const encodeStats = (d: StatsDisplay) => KEYS.map(k => (d[k] ? '1' : '0')).join('')
function decodeStats(s: string | undefined): StatsDisplay | null {
  if (!s || !/^[01]{5}$/.test(s)) return null
  return Object.fromEntries(KEYS.map((k, i) => [k, s[i] === '1'])) as unknown as StatsDisplay
}

/** "11100|11100" (total, average, hits, remaining, max possible). */
export const encodeDisplay = (d: ScoringDisplay) => `${encodeStats(d.mine)}|${encodeStats(d.others)}`

/** The saved choice; else the defaults, with 3.2.18's "show remaining and max possible" carried over when it was on. */
export function decodeDisplay(s: string | null | undefined, oldShowRemaining = false): ScoringDisplay {
  const [a, b] = (s ?? '').split('|')
  const mine = decodeStats(a), others = decodeStats(b)
  if (mine && others) return { mine, others }
  const d = { ...DEFAULT_STATS_DISPLAY, remaining: oldShowRemaining, maxPossible: oldShowRemaining }
  return { mine: d, others: { ...d } }
}

export const forCard = (d: ScoringDisplay, own: boolean) => (own ? d.mine : d.others)
