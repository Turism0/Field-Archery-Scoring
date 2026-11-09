// Rounds on this device and keeping them in step with the server (as the app's RoundRepository and RoundSyncer).
import { allRounds, everyRoundEvenDeleted, getKv, getRound, getRoundEvenDeleted, groupRounds, putRound, putRounds, setKv } from './db'
import { ApiError, request } from './api'
import { getAccount, signedOutByServer, type Friend } from './account'
import {
  newRound, nowUtc, roundFromJson, roundToJson, target, type AgeGroup, type ArcherClass, type GroupRole, type Round,
} from '../domain/round'
import { isComplete, totalPoints, type RoundType, type TargetScore } from '../domain/scoring'

// ---- on this device ----

export async function inProgress(): Promise<Round[]> {
  return (await allRounds()).filter(r => r.status === 'IN_PROGRESS' && (r.groupRole == null || r.groupRole === 'SCORER'))
    .sort((a, b) => b.updatedUtc.localeCompare(a.updatedUtc))
}

/** Your own finished rounds, newest first (not friends', guests' or copies of other phones' cards). */
export async function finished(): Promise<Round[]> {
  return (await allRounds()).filter(r => r.status === 'COMPLETED' && (r.groupRole == null || r.groupRole === 'SCORER'))
    .sort((a, b) => b.shootDate.localeCompare(a.shootDate) || b.createdUtc.localeCompare(a.createdUtc))
}

/** Everyone in a round's group, in scoring order (just the round if it isn't a group). */
export async function groupOf(r: Round): Promise<Round[]> {
  if (!r.groupId) return [r]
  return (await groupRounds(r.groupId)).sort((a, b) => (a.groupOrder ?? 0) - (b.groupOrder ?? 0))
}

export interface MemberDraft {
  role: 'FRIEND' | 'GUEST'
  name: string
  archerId?: string
  archerClass: ArcherClass
  ageGroup: AgeGroup
  bowStyle: string
}

/** A round on your own, or a group scored on this device (you first, then the others in order). */
export async function startRound(p: {
  shootDate: string; targetCount: number; archerClass: ArcherClass; ageGroup: AgeGroup; bowStyle: string; type: RoundType
  members?: MemberDraft[]; groupId?: string
}): Promise<string> {
  const groupId = p.members?.length || p.groupId ? (p.groupId ?? crypto.randomUUID()) : null
  const mine = newRound({ ...p, groupId, groupRole: groupId ? 'SCORER' : null, groupOrder: groupId ? 0 : null })
  const others = (p.members ?? []).map((m, i) => newRound({
    shootDate: p.shootDate, targetCount: p.targetCount, type: p.type, archerClass: m.archerClass, ageGroup: m.ageGroup,
    bowStyle: m.bowStyle, groupId, groupRole: m.role, groupName: m.name, groupArcherId: m.archerId ?? null, groupOrder: i + 1,
  }))
  await putRounds([mine, ...others])
  return mine.id
}

export async function addGroupRound(p: Parameters<typeof newRound>[0]) {
  if (p.id && await getRoundEvenDeleted(p.id)) return
  await putRound(newRound(p))
}

export async function saveTarget(id: string, t: TargetScore) {
  const r = await getRound(id)
  if (!r) return
  const targets = r.targets.filter(x => x.targetNo !== t.targetNo).concat(t).sort((a, b) => a.targetNo - b.targetNo)
  await putRound({ ...r, targets, updatedUtc: nowUtc(), pending: true, failed: false })
}

/** In a group, everyone not yet scored on this target gets the same target type. */
export async function setTypeForGroup(r: Round, t: TargetScore) {
  for (const m of await groupOf(r)) {
    if (m.id === r.id || m.groupRole === 'MIRROR') continue
    const theirs = target(m, t.targetNo)
    if (totalPoints(theirs) === 0 && theirs.animals.every(a => a.state === 'PENDING') && theirs.type !== t.type) {
      await saveTarget(m.id, { ...theirs, type: t.type, animals: t.animals.map(() => ({ state: 'PENDING' as const })) })
    }
  }
}

export async function setStatus(r: Round, status: 'IN_PROGRESS' | 'COMPLETED') {
  const ids = (await groupOf(r)).map(x => x.id)
  for (const id of ids) {
    const x = await getRound(id)
    if (x && x.status !== status) await putRound({ ...x, status, updatedUtc: nowUtc(), pending: true }, false)
  }
  await putRounds([])
}

export async function setGroupMember(id: string, role: GroupRole, name: string | null, order: number) {
  const r = await getRound(id)
  if (r) await putRound({ ...r, groupRole: role, groupName: name, groupOrder: order })
}

/** Deletes a round (in a group, everyone's). Your own goes from the server too on the next sync. */
export async function deleteRound(r: Round) {
  for (const x of await groupOf(r)) await putRound({ ...x, deleted: true, updatedUtc: nowUtc(), pending: true }, false)
  await putRounds([])
}

export function isScored(r: Round, n: number) { return isComplete(target(r, n)) }

// ---- sync ----

export interface SyncReport { ok: boolean; message?: string }

let syncing: Promise<SyncReport> | null = null

/** Uploads finished rounds, requests for friends, and deletions; downloads this account's rounds changed elsewhere. */
export function syncNow(): Promise<SyncReport> {
  syncing ??= doSync().finally(() => { syncing = null })
  return syncing
}

async function doSync(): Promise<SyncReport> {
  const account = await getAccount()
  if (!account) return { ok: false, message: 'Sign in (Profile) to back up your rounds.' }
  const token = account.token
  try {
    const me = await request('GET', '/api/me', undefined, token)
    await setKv('account', { ...account, role: me.role })
    await upload(token)
    await download(token)
    try {
      const f = await request('GET', '/api/friends', undefined, token)
      await setKv('friends', f.friends.map((x: any) => ({ archerId: x.archerId, username: x.username }) satisfies Friend))
    } catch (e) { if (e instanceof ApiError && (e.isNetwork || e.status === 401)) throw e }
    await setKv('lastSync', new Date().toISOString())
    return { ok: true }
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) { await signedOutByServer(); return { ok: false, message: 'Signed out: please sign in again.' } }
    return { ok: false, message: e instanceof Error ? e.message : String(e) }
  }
}

async function upload(token: string) {
  for (const r of await everyRoundEvenDeleted()) {
    if (!r.pending) continue
    const own = r.groupRole == null || r.groupRole === 'SCORER'
    const friend = r.groupRole === 'FRIEND'
    if (!own && !friend) continue   // guests' cards and copies of other phones' cards stay here
    if (!r.deleted && r.status !== 'COMPLETED') continue
    const editedAt = r.updatedUtc
    try {
      if (own) {
        if (r.deleted) await request('DELETE', `/api/rounds/${r.id}`, undefined, token)
        else await request('PUT', `/api/rounds/${r.id}`, roundToJson(r), token)
      } else if (r.deleted) {
        try { await request('DELETE', `/api/offers/${r.id}`, undefined, token) } catch (e) { if (!(e instanceof ApiError && e.status === 404)) throw e }
      } else if (r.groupArcherId) {
        try {
          await request('PUT', `/api/offers/${r.id}`, { forArcherId: r.groupArcherId, groupId: r.groupId ?? null, round: roundToJson(r) }, token)
        } catch (e) { if (!(e instanceof ApiError && e.status === 409)) throw e }   // 409: they've accepted it already
      }
      await markSynced(r.id, editedAt, false)
    } catch (e) {
      // Refused (invalid, or not yours / not a friend): not tried again until it's edited.
      if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429) await markSynced(r.id, editedAt, true)
      else throw e
    }
  }
}

/** Only if it hasn't been edited since it was sent. */
async function markSynced(id: string, editedAt: string, failed: boolean) {
  const r = await getRoundEvenDeleted(id)
  if (r && r.updatedUtc === editedAt) await putRound({ ...r, pending: false, failed }, false)
}

async function download(token: string) {
  const saved = await getKv<string>('cursor')
  // A few minutes before the last cursor, so a change saved as we last downloaded isn't missed.
  let since: string | null = saved ? new Date(new Date(saved).getTime() - 300_000).toISOString() : null
  let afterId: string | null = null
  let newest = saved ?? null
  for (;;) {
    const q: string = [since ? `since=${encodeURIComponent(since)}` : '', afterId ? `afterId=${afterId}` : '', 'limit=50'].filter(Boolean).join('&')
    const page: any = await request('GET', `/api/rounds?${q}`, undefined, token)
    for (const j of page.rounds as any[]) {
      try { await applyRemote(j) } catch { /* a round this version can't read: skipped */ }
      since = j.serverUpdatedUtc as string; afterId = j.roundId as string
      if (!newest || since! > newest) newest = since
    }
    if (!page.more) break
  }
  if (newest) await setKv('cursor', newest)
  await putRounds([])
}

/** One downloaded round, unless this device has the same or a newer edit. */
async function applyRemote(j: any) {
  const local = await getRoundEvenDeleted(j.roundId)
  if (local && new Date(local.updatedUtc).getTime() >= new Date(j.clientUpdatedUtc).getTime()) return
  if (j.isDeleted) {
    if (local) await putRound({ ...local, deleted: true, updatedUtc: j.clientUpdatedUtc, pending: false }, false)
    return
  }
  const r = roundFromJson(j)
  // Keep its place in a group on this device (the server doesn't send it back).
  const merged: Round = local?.groupId
    ? { ...r, groupId: local.groupId, groupRole: local.groupRole, groupName: local.groupName, groupArcherId: local.groupArcherId, groupOrder: local.groupOrder }
    : r
  await putRound(merged, false)
}

/** Sync every few minutes while the app is open, and when signal comes back. */
export function startAutoSync() {
  const go = () => { if (navigator.onLine) syncNow() }
  window.addEventListener('online', go)
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') go() })
  setInterval(go, 5 * 60_000)
  go()
}
