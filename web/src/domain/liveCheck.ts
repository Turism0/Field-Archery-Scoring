// Live groups with no signal on the web (2.8): the app's LiveRules (matching, checks by hand) and LiveCheckQr
// (checking scores by QR). Keep in step with LiveRules.kt and LiveCheckQr.kt; test/liveCheck.test.ts has the same cases.
import {
  FUN_MAX, MAX_FAN, MIN_FAN, allowedHits, animalPoints, arrowsAvailable, isComplete, totalPoints, woodsmanValid,
  type AnimalResult, type Hit, type TargetScore, type TargetType,
} from './scoring'

export const WAITING = 'WAITING', MATCHED = 'MATCHED', MISMATCH = 'MISMATCH'
/** Checks by hand: this many targets entered here that haven't been compared with the other scorer. */
export const MANUAL_CHECK_TARGETS = 5

export interface CheckEntry { targetNo: number; cardMemberId: string; scorerMemberId: string; target: TargetScore; enteredUtc: string }
export const cellKey = (n: number, card: string) => `${n}|${card}`

/** What has to match for two entries to be the same score (points could hide a different hit). */
export function matchKey(t: TargetScore): string {
  return [
    t.type, t.overridePoints ?? '-', totalPoints(t), t.type === 'WOODSMAN' ? t.arrows : '',
    t.animals.map((a, i) => `${i}:${a.state}:${a.state === 'SCORED' ? a.arrowNo : ''}:${a.state === 'SCORED' ? a.hit : ''}:${animalPoints(a)}`).join(';'),
  ].join('|')
}

/**
 * Each card's score on each target (as the server's Cells and the app's LiveRules.cells): MATCHED when every scorer for
 * the card has entered the same score, MISMATCH when two differ, WAITING while a scorer still has to enter it.
 */
export function cells(entries: CheckEntry[], scorers: Record<string, string[]>): Record<string, string> {
  const groups = new Map<string, CheckEntry[]>()
  for (const e of entries) {
    const k = cellKey(e.targetNo, e.cardMemberId)
    groups.set(k, [...(groups.get(k) ?? []), e])
  }
  const out: Record<string, string> = {}
  for (const [k, list] of groups) {
    const assigned = scorers[list[0].cardMemberId] ?? []
    const counted = assigned.length ? list.filter(e => assigned.includes(e.scorerMemberId)) : list.slice(0, 1)
    const needed = Math.max(1, assigned.length)
    const same = (l: CheckEntry[]) => new Set(l.map(e => matchKey(e.target))).size === 1
    out[k] = counted.length < needed
      ? (assigned.length && list.length >= needed && same(list) ? MATCHED : WAITING)
      : same(counted) ? MATCHED : MISMATCH
  }
  return out
}

/** Newest wins (by when it was entered on its phone). */
export function mergeEntries(mine: CheckEntry[], incoming: CheckEntry[]): CheckEntry[] {
  const out = new Map(mine.map(e => [`${e.targetNo}|${e.cardMemberId}|${e.scorerMemberId}`, e]))
  for (const e of incoming) {
    const k = `${e.targetNo}|${e.cardMemberId}|${e.scorerMemberId}`
    const have = out.get(k)
    if (!have || have.enteredUtc < e.enteredUtc) out.set(k, e)
  }
  return [...out.values()]
}

/**
 * Checks by hand: targets this scorer has entered for cards shared with another scorer whose entry hasn't arrived, and
 * that haven't been checked by hand. With five or more, the oldest five are due.
 */
export function dueManualCheck(me: string, entries: CheckEntry[], scorers: Record<string, string[]>, checkedByHand: Set<string>): number[] {
  const c = cells(entries, scorers)
  const due = [...new Set(entries
    .filter(e => e.scorerMemberId === me && (scorers[e.cardMemberId] ?? []).length >= 2)
    .filter(e => c[cellKey(e.targetNo, e.cardMemberId)] === WAITING && !checkedByHand.has(cellKey(e.targetNo, e.cardMemberId)))
    .filter(e => isComplete(e.target))
    .map(e => e.targetNo))].sort((a, b) => a - b)
  return due.length >= MANUAL_CHECK_TARGETS ? due.slice(0, MANUAL_CHECK_TARGETS) : []
}

// ---- the QR (fieldarchery:check:1:<group>:<scorer>:<target>.<card>.<score>;…) ----

export const CHECK_PREFIX = 'fieldarchery:check:1:'
export const short = (id: string) => id.replace(/-/g, '').toLowerCase().slice(0, 8)
export const isCheckQr = (text: string) => text.trim().startsWith(CHECK_PREFIX)

export interface CheckItem { targetNo: number; card: string; target: TargetScore }
export interface CheckPayload { group: string; scorer: string; items: CheckItem[] }

const TYPE_LETTER: Record<TargetType, string> = { STANDARD: 'S', PREDATOR_PREY: 'P', FAN: 'F', WOODSMAN: 'W', FUN: 'X' }
const HIT_LETTER: Record<Hit, string> = { INNER_KILL: 'I', KILL: 'K', WOUND: 'W' }

export function encodeTarget(t: TargetScore): string {
  const body = t.type === 'WOODSMAN' ? t.arrows : t.type === 'FUN' ? ''
    : t.animals.map(a => a.state === 'PENDING' ? '-' : a.state === 'MISSED' ? 'M' : `${a.arrowNo}${HIT_LETTER[a.hit]}`).join(',')
  return TYPE_LETTER[t.type] + body + (t.overridePoints != null ? `=${t.overridePoints}` : '')
}

export function encodeCheck(groupId: string, scorerId: string, entries: { card: string; target: TargetScore }[]): string {
  return CHECK_PREFIX + short(groupId) + ':' + short(scorerId) + ':' + [...entries]
    .sort((a, b) => a.target.targetNo - b.target.targetNo || (a.card < b.card ? -1 : a.card > b.card ? 1 : 0))
    .map(e => `${e.target.targetNo}.${short(e.card)}.${encodeTarget(e.target)}`).join(';')
}

export function decodeTarget(targetNo: number, code: string): TargetScore | null {
  const eq = code.indexOf('=')
  const override = eq >= 0 ? Number(code.slice(eq + 1)) : null
  if (override != null && (!Number.isInteger(override) || override < 0 || code.slice(eq + 1) === '')) return null
  const main = eq >= 0 ? code.slice(0, eq) : code
  const type = (Object.keys(TYPE_LETTER) as TargetType[]).find(k => TYPE_LETTER[k] === main[0])
  if (!type) return null
  const body = main.slice(1)
  if (type === 'WOODSMAN') return /^[WKM]*$/.test(body) && woodsmanValid(body) ? { targetNo, type, animals: [], overridePoints: override, arrows: body } : null
  if (type === 'FUN') return body === '' && (override == null || override <= FUN_MAX) ? { targetNo, type, animals: [], overridePoints: override, arrows: '' } : null
  const animals: AnimalResult[] = []
  for (const a of body.split(',')) {
    if (a === '-') animals.push({ state: 'PENDING' })
    else if (a === 'M') animals.push({ state: 'MISSED' })
    else {
      const hit = (Object.keys(HIT_LETTER) as Hit[]).find(h => HIT_LETTER[h] === a[1])
      const arrowNo = Number(a[0])
      if (a.length !== 2 || !hit || !allowedHits(arrowNo).includes(hit)) return null
      animals.push({ state: 'SCORED', arrowNo, hit })
    }
  }
  const count = type === 'STANDARD' ? animals.length === 1 : type === 'PREDATOR_PREY' ? animals.length === 2 : animals.length >= MIN_FAN && animals.length <= MAX_FAN
  if (!count) return null
  if (animals.some((x, i) => x.state === 'SCORED' && x.arrowNo > arrowsAvailable(type, animals, i))) return null
  return { targetNo, type, animals, overridePoints: override, arrows: '' }
}

/** Null when the text isn't a check QR or doesn't read cleanly. */
export function decodeCheck(text: string): CheckPayload | null {
  const s = text.trim()
  if (!s.startsWith(CHECK_PREFIX)) return null
  const rest = s.slice(CHECK_PREFIX.length)
  const a = rest.indexOf(':'), b = rest.indexOf(':', a + 1)
  if (a < 0 || b < 0) return null
  const group = rest.slice(0, a), scorer = rest.slice(a + 1, b), list = rest.slice(b + 1)
  if (group.length !== 8 || scorer.length !== 8) return null
  const items: CheckItem[] = []
  if (list) for (const e of list.split(';')) {
    const f = e.split('.')
    const n = Number(f[0])
    if (f.length !== 3 || f[1].length !== 8 || !Number.isInteger(n) || n < 1) return null
    const t = decodeTarget(n, f[2])
    if (!t) return null
    items.push({ targetNo: n, card: f[1], target: t })
  }
  return { group, scorer, items }
}

export interface CheckDifference { targetNo: number; cardMemberId: string; mine: TargetScore; theirs: TargetScore }
export interface CheckResult { matched: string[]; differences: CheckDifference[]; notInTheirs: string[] }

/** Compares this phone's entries waiting for a check ("target|card" → score) with a scanned QR. */
export function compareCheck(mine: Record<string, TargetScore>, theirs: CheckPayload): CheckResult {
  const by = new Map(theirs.items.map(i => [`${i.targetNo}|${i.card}`, i.target]))
  const out: CheckResult = { matched: [], differences: [], notInTheirs: [] }
  const keys = Object.keys(mine).sort((x, y) => Number(x.split('|')[0]) - Number(y.split('|')[0]) || (x < y ? -1 : x > y ? 1 : 0))
  for (const k of keys) {
    const [n, card] = [Number(k.split('|')[0]), k.slice(k.indexOf('|') + 1)]
    const t = mine[k]
    const other = by.get(`${n}|${short(card)}`)
    if (!other || !isComplete(other)) out.notInTheirs.push(k)
    else if (matchKey(other) === matchKey(t)) out.matched.push(k)
    else out.differences.push({ targetNo: n, cardMemberId: card, mine: t, theirs: other })
  }
  return out
}

/** Why a scanned QR can't be used here, or null when it can. */
export function checkProblem(p: CheckPayload, groupId: string, me: string, cards: string[], scorersOf: (card: string) => string[]): string | null {
  if (p.group !== short(groupId)) return 'That QR is from a different group.'
  if (p.scorer === short(me)) return "That's this phone's own QR. Scan the other scorer's phone."
  if (!cards.some(c => scorersOf(c).some(s => short(s) === p.scorer))) return "That QR is from someone who doesn't score these cards. Scan the other scorer of them."
  return null
}
