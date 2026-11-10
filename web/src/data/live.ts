// Live groups through the server (web app 2.5; the Android app also links phone to phone, which a browser can't).
// Same endpoints and rules as the app: the server shares out scorers and matches double / 3+ scorers' entries.
// With no signal (2.8) the group carries on from the last copy kept here: this phone's entries are kept and sent when
// there's signal, matching is worked out here (domain/liveCheck.ts, as the app's LiveRules), and double / 3+ scorers
// check by hand every 5 targets, reading the scores out or scanning each other's check QR.
import { getKv, groupRounds, setKv } from './db'
import { ApiError, request } from './api'
import { getAccount } from './account'
import { addGroupRound, saveTarget, setGroupMember, setGroupStartTarget, setStatus, deleteRound } from './rounds'
import { getRound } from './db'
import { targetFromJson, targetToJson, nowUtc, type AgeGroup, type ArcherClass, type GroupRole, type Round } from '../domain/round'
import { isBlank, isComplete, totalPoints, type RoundType, type TargetScore } from '../domain/scoring'
import { WAITING, cellKey, cells as workOutCells, dueManualCheck as dueChecks, mergeEntries, type CheckEntry } from '../domain/liveCheck'

export type LiveMethod = 'SINGLE' | 'DOUBLE' | 'MULTI' | 'SELF'
export const METHODS: { code: LiveMethod; name: string; help: string; checked: boolean }[] = [
  { code: 'SINGLE', name: 'One scorer', help: 'You score everyone; the others watch on their own phones.', checked: false },
  { code: 'DOUBLE', name: 'Double scoring', help: 'You and the first archer to join both enter every score; each is saved when your two match.', checked: true },
  { code: 'MULTI', name: 'Three or more scorers', help: 'Everyone on their own phone scores two others (never themselves), shared out evenly; each score is saved when its two scorers match. Needs 3+ archers on their own phones.', checked: true },
  { code: 'SELF', name: 'Self scoring', help: 'Everyone scores their own card on their own phone; you score friends and guests you added.', checked: false },
]
export const methodOf = (code: string) => METHODS.find(m => m.code === code)

const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
export const newCode = () => Array.from({ length: 6 }, () => CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)]).join('')
/** A code typed or scanned: the last 6 code characters (a QR holds "fieldarchery:join:CODE"). */
export const cleanCode = (text: string) => [...text.toUpperCase()].filter(c => CODE_ALPHABET.includes(c)).join('').slice(-6)
export const qrText = (code: string) => `fieldarchery:join:${code}`
/** A scanned QR is a join code (what the app and web app show in a lobby). */
export const isJoinQr = (text: string) => /^fieldarchery:join:[A-Z0-9]{6}$/i.test(text.trim())

export interface LiveMember {
  memberId: string; kind: string; archerId: string | null; name: string
  archerClass: ArcherClass; ageGroup: AgeGroup; bowStyle: string; sortOrder: number; scorers: string[]
}
export interface LiveEntry { targetNo: number; cardMemberId: string; scorerMemberId: string; target: TargetScore; points: number; enteredUtc: string }

export interface LiveState {
  groupId: string; code: string; method: string; roundType: RoundType; targetCount: number; shootDate: string
  locationId: string | null; locationName: string | null; status: string; myMemberId: string; myKind: string
  members: LiveMember[]; entries: LiveEntry[]; cells: Record<string, string>   // "target|card" → WAITING / MATCHED / MISMATCH
  /** The target the group started on (2.7), set by the host; undefined from servers before 2.7. */
  startTarget?: number | null
  /** "target|card" this phone's scorer checked by hand with the other scorer, with no signal (2.8). */
  manualChecks: string[]
  /** No signal: this is the last copy kept here, with this phone's entries since and matching worked out here (2.8). */
  offline: boolean
}

/** What this device keeps about a live group. */
interface LiveInfo { memberId: string; code: string; method: string; host: boolean; expected?: number }
const infos = async () => (await getKv<Record<string, LiveInfo>>('live')) ?? {}
async function setInfo(gid: string, info: LiveInfo | null) {
  const all = await infos()
  if (info) all[gid] = info; else delete all[gid]
  await setKv('live', all)
}
export const liveInfo = async (gid: string | null | undefined) => (gid ? (await infos())[gid] : undefined)
export const isLive = async (gid: string | null | undefined) => !!(await liveInfo(gid))

// ---- helpers on the state ----
export const isHost = (s: LiveState) => s.myKind === 'HOST'
export const checked = (s: LiveState) => methodOf(s.method)?.checked ?? false
export const member = (s: LiveState, id: string) => s.members.find(m => m.memberId === id)
export const canScore = (s: LiveState, card: string) => s.status === 'STARTED' && !!member(s, card)?.scorers.includes(s.myMemberId)
export const hasPhone = (m: LiveMember) => m.kind === 'HOST' || m.kind === 'JOINED'
export const canTakeOver = (s: LiveState) => !isHost(s) && s.myKind === 'JOINED' && s.status !== 'CLOSED'
export const cell = (s: LiveState, n: number, card: string) => s.cells[`${n}|${card}`]

export function scorerNames(s: LiveState, card: string): string[] {
  return (member(s, card)?.scorers ?? []).map(id => id === s.myMemberId ? 'You' : member(s, id)?.name ?? '?')
}

/** Double / 3+ scorers: where a card's score on a target has got to. */
export function checkText(s: LiveState, n: number, card: string, iEntered: boolean): string | null {
  if (!checked(s) || s.status !== 'STARTED') return null
  const scorers = member(s, card)?.scorers ?? []
  if (!scorers.length) return null
  const entered = s.entries.filter(e => e.targetNo === n && e.cardMemberId === card && scorers.includes(e.scorerMemberId))
  const name = (id: string) => id === s.myMemberId ? 'you' : member(s, id)?.name ?? '?'
  const state = cell(s, n, card)
  let text: string
  if (state === 'MATCHED') text = 'Both scores match: saved.'
  else if (state === 'MISMATCH') {
    const same = new Set(entered.map(e => e.points)).size === 1
    text = `Scores don't match (${entered.map(e => `${name(e.scorerMemberId)} ${e.points}`).join(', ')}${same ? ', same points but different hits' : ''}). Check with each other and re-enter.`
  } else {
    const missing = scorers.filter(id => !entered.some(e => e.scorerMemberId === id) && !(id === s.myMemberId && iEntered))
    text = !missing.length ? 'Checking…'
      : s.manualChecks.includes(cellKey(n, card))
        ? `Checked by hand and saved here. It's compared with ${missing.map(name).join(' and ')}'s when there's signal.`
        : `Waiting for ${missing.map(name).join(' and ')} to enter${iEntered ? ' (yours is in).' : '.'}`
  }
  return text[0].toUpperCase() + text.slice(1)
}

async function token(): Promise<string> {
  const a = await getAccount()
  if (!a) throw new ApiError(401, 'not_signed_in', 'Sign in (Profile) to use live groups.')
  return a.token
}

// ---- starting and joining ----

/** The host's group for the server: you, friends and guests (archers on their own phones join themselves). */
async function groupBody(gid: string, info: LiveInfo, status: string) {
  const local = await groupRounds(gid)
  const account = await getAccount()
  const mine = local.find(r => r.groupRole === 'SCORER')!
  const members = local.filter(r => r.groupRole === 'SCORER' || r.groupRole === 'FRIEND' || r.groupRole === 'GUEST').map(r => {
    const kind = r.groupRole === 'SCORER' ? 'HOST' : r.groupRole!
    return {
      memberId: r.id, kind, archerId: kind === 'HOST' ? account?.archerId ?? null : kind === 'FRIEND' ? r.groupArcherId ?? null : null,
      guestName: kind === 'GUEST' ? r.groupName : null, archerClass: r.archerClass === 'LADIES' ? 'L' : 'G',
      ageGroup: r.ageGroup, bowStyle: r.bowStyle, roundId: r.id,
    }
  })
  return {
    code: info.code, method: info.method, roundType: mine.type, targetCount: mine.targetCount, shootDate: mine.shootDate,
    locationId: mine.locationId ?? null, status, members, startTarget: mine.startTarget ?? null,
  }
}

/** Host: puts the group (already on this device, made by startRound) on the server with a new code. */
export async function create(gid: string, method: LiveMethod, expected: number): Promise<string> {
  const t = await token()
  const mine = (await groupRounds(gid)).find(r => r.groupRole === 'SCORER')!
  for (let i = 0; i < 5; i++) {
    const info: LiveInfo = { memberId: mine.id, code: newCode(), method, host: true, expected }
    try {
      await request('PUT', `/api/groups/${gid}`, await groupBody(gid, info, 'OPEN'), t)
      await setInfo(gid, info)
      return info.code
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 'code_taken')) throw e
    }
  }
  throw new ApiError(409, 'code_taken', "Couldn't find a free code. Try again.")
}

async function putStatus(gid: string, status: string) {
  const info = await liveInfo(gid)
  if (!info) throw new ApiError(404, 'no_such_group', "That group isn't on this device.")
  await request('PUT', `/api/groups/${gid}`, await groupBody(gid, info, status), await token())
}

/** Host: start scoring (the server shares out who scores which card). */
export const start = (gid: string) => putStatus(gid, 'STARTED')
/** Host: close the group: the code stops working, every phone finishes its rounds. */
export const close = (gid: string) => putStatus(gid, 'CLOSED')

/** Join with a code. Makes your round and copies of everyone else's. Returns the group id. */
export async function join(code: string, archerClass: ArcherClass, ageGroup: AgeGroup, bowStyle: string): Promise<string> {
  const t = await token()
  const memberId = crypto.randomUUID()
  let json: any
  try {
    json = await request('POST', '/api/groups/join', {
      code: cleanCode(code), memberId, archerClass: archerClass === 'LADIES' ? 'L' : 'G', ageGroup, bowStyle, roundId: memberId,
    }, t)
  } catch (e) {
    if (e instanceof ApiError && e.code === 'is_host') {
      // Your own group from another phone: carry on as its host here.
      const mine = ((await request('GET', '/api/groups', undefined, t)).groups as any[])
        .find(g => g.code === cleanCode(code) && g.myKind === 'HOST' && g.status !== 'CLOSED')
      if (!mine) throw e
      json = await request('GET', `/api/groups/${mine.groupId}`, undefined, t)
      await setInfo(json.groupId, { memberId: json.me.memberId, code: json.code, method: json.method, host: true })
      await apply(parse(json))
      return json.groupId
    }
    throw e
  }
  // Joining again keeps your first member id (and so your card).
  await setInfo(json.groupId, { memberId: json.me.memberId, code: json.code, method: json.method, host: false })
  await apply(parse(json))
  return json.groupId
}

/** Leave: before the start you're taken out; once started your card stays, so joining again gets it back. */
export async function leave(gid: string, started: boolean) {
  const info = await liveInfo(gid)
  if (info && !started) { try { await request('DELETE', `/api/groups/${gid}/members/${info.memberId}`, undefined, await token()) } catch { /* gone anyway */ } }
  await setInfo(gid, null)
}

/** The host's phone has gone: this one takes over as host. */
export async function takeOver(gid: string) {
  const info = await liveInfo(gid)
  if (!info) return
  await request('POST', `/api/groups/${gid}/host`, undefined, await token())
  await setInfo(gid, { ...info, host: true })
}

// ---- following the group ----

/** Fetches the group, sends any scores still waiting, and copies saved scores into this device's rounds. */
export async function refresh(gid: string): Promise<LiveState> {
  const t = await token()
  await flush(gid, t)
  const json = await request('GET', `/api/groups/${gid}`, undefined, t)
  // Kept for when there's no signal (2.8).
  await setKv(`liveState:${gid}`, json)
  const s = { ...parse(json), manualChecks: await manualChecks(gid) }
  await apply(s)
  return s
}

/**
 * No signal (2.8): the group as it was last heard from the server, plus this phone's entries since, with matching
 * worked out here (as the app does). Null when this phone has never had the group from the server.
 */
export async function offlineState(gid: string): Promise<LiveState | null> {
  const json = await getKv<any>(`liveState:${gid}`)
  if (!json) return null
  const base = parse(json)
  const mine: CheckEntry[] = Object.values(await myTimedEntries(gid)).map(e => ({
    targetNo: e.targetNo, cardMemberId: e.cardMemberId, scorerMemberId: base.myMemberId, target: e.target, enteredUtc: e.enteredUtc,
  }))
  const entries = mergeEntries(base.entries, mine)
  const scorers = Object.fromEntries(base.members.map(m => [m.memberId, m.scorers]))
  const s: LiveState = {
    ...base, offline: true, manualChecks: await manualChecks(gid),
    entries: entries.map(e => ({ ...e, points: totalPoints(e.target) })),
    cells: workOutCells(entries, scorers),
  }
  await apply(s)
  return s
}

// ---- checks by hand with no signal (2.8) ----

const manualChecks = async (gid: string) => (await getKv<string[]>(`liveChecked:${gid}`)) ?? []

/** The scorers agree on these ("target|card"): saved here, compared again when there's signal. */
export async function markChecked(gid: string, keys: string[]) {
  const have = new Set(await manualChecks(gid))
  keys.forEach(k => have.add(k))
  await setKv(`liveChecked:${gid}`, [...have])
}

const scorersOf = (s: LiveState) => Object.fromEntries(s.members.map(m => [m.memberId, m.scorers]))

/** Double / 3+ scorers: targets due a check by hand (five entered here not yet compared). Empty when none are due. */
export function dueManualCheck(s: LiveState): number[] {
  if (!checked(s) || s.status !== 'STARTED') return []
  return dueChecks(s.myMemberId, s.entries, scorersOf(s), new Set(s.manualChecks))
}

/**
 * This phone's complete entries for cards shared with another scorer that are still waiting for the other scorer's
 * ("target|card" → score). [targets]: only these; [includeChecked]: also those already checked by hand.
 */
export function waitingForCheck(s: LiveState, opts: { targets?: number[]; includeChecked?: boolean } = {}): Record<string, TargetScore> {
  const out: Record<string, TargetScore> = {}
  for (const e of s.entries) {
    const k = cellKey(e.targetNo, e.cardMemberId)
    if (e.scorerMemberId !== s.myMemberId || (member(s, e.cardMemberId)?.scorers.length ?? 0) < 2 || !isComplete(e.target)) continue
    if (s.cells[k] !== WAITING || (!opts.includeChecked && s.manualChecks.includes(k))) continue
    if (opts.targets && !opts.targets.includes(e.targetNo)) continue
    out[k] = e.target
  }
  return out
}

/** The other scorers of these cards (for "Check with Sue"). */
export function otherScorerNames(s: LiveState, cards: string[]): string[] {
  return [...new Set(cards.flatMap(c => member(s, c)?.scorers ?? []))].filter(id => id !== s.myMemberId).map(id => member(s, id)?.name ?? '?')
}

/** A score this device entered for a card: kept and sent again if there's no signal. */
export async function send(gid: string, card: string, target: TargetScore) {
  const info = await liveInfo(gid)
  if (!info) return
  const pending = (await getKv<Record<string, any>>(`livePending:${gid}`)) ?? {}
  pending[`${target.targetNo}|${card}`] = { targetNo: target.targetNo, cardMemberId: card, scorerMemberId: info.memberId, target: targetToJson(target), enteredUtc: nowUtc() }
  await setKv(`livePending:${gid}`, pending)
  const mine = await myEntries(gid)
  mine[`${target.targetNo}|${card}`] = target
  await setKv(`liveMine:${gid}`, mine)
  // With the time it was entered, for matching with no signal (2.8).
  const timed = await myTimedEntries(gid)
  timed[`${target.targetNo}|${card}`] = { targetNo: target.targetNo, cardMemberId: card, target, enteredUtc: pending[`${target.targetNo}|${card}`].enteredUtc }
  await setKv(`liveMineAt:${gid}`, timed)
  try { await flush(gid, await token()) } catch { /* sent with the next refresh */ }
}

interface TimedEntry { targetNo: number; cardMemberId: string; target: TargetScore; enteredUtc: string }
const myTimedEntries = async (gid: string) => (await getKv<Record<string, TimedEntry>>(`liveMineAt:${gid}`)) ?? {}

/** This device's own entries (shown on the cards it scores until the other scorer matches them). */
export async function myEntries(gid: string): Promise<Record<string, TargetScore>> {
  return (await getKv<Record<string, TargetScore>>(`liveMine:${gid}`)) ?? {}
}

async function flush(gid: string, t: string) {
  const pending = (await getKv<Record<string, any>>(`livePending:${gid}`)) ?? {}
  const keys = Object.keys(pending)
  if (!keys.length) return
  await request('PUT', `/api/groups/${gid}/entries`, { entries: keys.map(k => pending[k]) }, t)
  const now = (await getKv<Record<string, any>>(`livePending:${gid}`)) ?? {}
  for (const k of keys) if (now[k]?.enteredUtc === pending[k].enteredUtc) delete now[k]
  await setKv(`livePending:${gid}`, now)
}

function parse(j: any): LiveState {
  return {
    manualChecks: [], offline: false,
    groupId: j.groupId, code: j.code, method: j.method, roundType: j.roundType === 'WOODSMAN' ? 'WOODSMAN' : 'BIG_GAME',
    targetCount: j.targetCount, shootDate: String(j.shootDate).slice(0, 10), locationId: j.locationId ?? null,
    locationName: j.locationName ?? null, status: j.status, myMemberId: j.me.memberId, myKind: j.me.kind,
    startTarget: j.startTarget === undefined ? undefined : j.startTarget ?? null,
    members: (j.members as any[]).map(m => ({
      memberId: m.memberId, kind: m.kind, archerId: m.archerId ?? null, name: m.name ?? '?',
      archerClass: (m.archerClass === 'L' ? 'LADIES' : 'GENTS') as ArcherClass, ageGroup: m.ageGroup, bowStyle: m.bowStyle,
      sortOrder: m.sortOrder, scorers: m.scorers ?? [],
    })).sort((a, b) => a.sortOrder - b.sortOrder),
    entries: ((j.entries ?? []) as any[]).map(e => ({
      targetNo: e.targetNo, cardMemberId: e.cardMemberId, scorerMemberId: e.scorerMemberId,
      target: targetFromJson(e.target), points: e.points, enteredUtc: e.enteredUtc,
    })),
    cells: Object.fromEntries(((j.cells ?? []) as any[]).map(c => [`${c.targetNo}|${c.cardMemberId}`, c.state])),
  }
}

/**
 * Brings this device's rounds in line with the group (as the app): a round for every member, saved scores copied into
 * cards this device doesn't score (double / 3+ scorers: every card once both match). Closed: everyone's round finished.
 */
async function apply(s: LiveState) {
  const info = await liveInfo(s.groupId)
  if (!info) return
  const local = new Map((await groupRounds(s.groupId)).map(r => [r.id, r]))
  for (const m of s.members) {
    const isMe = m.memberId === s.myMemberId
    const role: GroupRole = isMe ? 'SCORER' : m.kind === 'FRIEND' && isHost(s) ? 'FRIEND' : m.kind === 'GUEST' && isHost(s) ? 'GUEST' : 'MIRROR'
    const existing = local.get(m.memberId)
    if (!existing) {
      await addGroupRound({
        id: m.memberId, groupId: s.groupId, groupRole: role, groupName: isMe ? null : m.name, groupOrder: m.sortOrder,
        shootDate: s.shootDate, targetCount: s.targetCount, type: s.roundType, archerClass: m.archerClass, ageGroup: m.ageGroup,
        bowStyle: m.bowStyle, locationId: s.locationId, locationName: s.locationName, startTarget: s.startTarget ?? null,
      })
    } else if (existing.groupRole !== role || existing.groupOrder !== m.sortOrder || (!isMe && existing.groupName !== m.name)) {
      await setGroupMember(m.memberId, role, isMe ? null : m.name, m.sortOrder)
    }
  }
  // Start target (2.7): the host's round decides (sent with the group); everyone else takes it.
  if (!isHost(s) && s.startTarget !== undefined) await setGroupStartTarget(s.groupId, s.startTarget)
  // This device's own entries already on the server (e.g. after reloading).
  const mine = await myEntries(s.groupId)
  let added = false
  for (const e of s.entries) {
    const k = `${e.targetNo}|${e.cardMemberId}`
    if (e.scorerMemberId === s.myMemberId && !mine[k]) { mine[k] = e.target; added = true }
  }
  if (added) await setKv(`liveMine:${s.groupId}`, mine)
  // Saved scores: matched, or (no signal, 2.8) checked by hand on this phone.
  const waiting = (await getKv<Record<string, any>>(`livePending:${s.groupId}`)) ?? {}
  for (const [k, state] of Object.entries(s.cells)) {
    const byHand = state === WAITING && s.manualChecks.includes(k)
    if (state !== 'MATCHED' && !byHand) continue
    const [n, card] = [Number(k.split('|')[0]), k.split('|')[1]]
    const scorers = member(s, card)?.scorers ?? []
    const pick = byHand ? s.entries.find(e => e.targetNo === n && e.cardMemberId === card && e.scorerMemberId === s.myMemberId)
      : s.entries.filter(e => e.targetNo === n && e.cardMemberId === card && (!scorers.length || scorers.includes(e.scorerMemberId)))
        .sort((a, b) => b.enteredUtc.localeCompare(a.enteredUtc))[0]
    if (!pick || (waiting[k] && !byHand && !s.offline)) continue
    const r = await getRound(card)
    if (!r) continue
    const current = r.targets.find(t => t.targetNo === n)
    // Cards this device scores are saved as they're entered; only blanks are filled in (joined again).
    if (!checked(s) && canScore(s, card) && current && !isBlank(current)) continue
    if (!current || JSON.stringify(targetToJson(current)) !== JSON.stringify(targetToJson(pick.target))) await saveTarget(card, pick.target)
  }
  if (s.status === 'CLOSED') {
    const all = await groupRounds(s.groupId)
    const me = all.find(r => r.id === s.myMemberId)
    if (me) {
      if (all.every(r => r.targets.every(t => totalPoints(t) === 0 && isBlank(t)))) await deleteRound(me)
      else await setStatus(me, 'COMPLETED')
    }
    await setInfo(s.groupId, null)
  }
}

export type { Round }
