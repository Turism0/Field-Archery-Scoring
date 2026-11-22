// Equipment (3.2.24, Pete): your bows and arrows, kept in this browser (kv 'equipment') and synced with your account
// (private to you). Deleting keeps them, flagged, so rounds shot with them still show their names. Put away and brought
// back with the account's other things at sign-out / sign-in (data/account.ts). As the app's EquipmentRepository.
import { getKv, setKv } from './db'
import { ApiError, request } from './api'
import type { ArrowSet, Bow } from '../domain/equipment'
import { nowUtc } from '../domain/round'

export interface EquipmentStore { bows: Bow[]; arrows: ArrowSet[] }

export const getEquipment = async (): Promise<EquipmentStore> => {
  const e = await getKv<EquipmentStore>('equipment')
  return { bows: e?.bows ?? [], arrows: e?.arrows ?? [] }
}

async function save(e: EquipmentStore) { await setKv('equipment', e.bows.length || e.arrows.length ? e : null) }

export async function saveBow(b: Bow) {
  const e = await getEquipment()
  await save({ ...e, bows: [...e.bows.filter(x => x.id !== b.id), { ...b, updatedUtc: nowUtc(), pending: true }] })
}

export async function saveArrows(a: ArrowSet) {
  const e = await getEquipment()
  await save({ ...e, arrows: [...e.arrows.filter(x => x.id !== a.id), { ...a, updatedUtc: nowUtc(), pending: true }] })
}

export async function deleteBow(id: string) {
  const e = await getEquipment()
  await save({ ...e, bows: e.bows.map(b => (b.id === id ? { ...b, deleted: true, updatedUtc: nowUtc(), pending: true } : b)) })
}

export async function deleteArrows(id: string) {
  const e = await getEquipment()
  await save({ ...e, arrows: e.arrows.map(a => (a.id === id ? { ...a, deleted: true, updatedUtc: nowUtc(), pending: true } : a)) })
}

/** Signing in (data/account.ts): what was put away for the account joins what's here (the newer copy of each wins). */
export function mergeEquipment(a: EquipmentStore | null | undefined, b: EquipmentStore | null | undefined): EquipmentStore {
  const pick = <T extends { id: string; updatedUtc: string }>(x: T[], y: T[]) => {
    const m = new Map<string, T>()
    for (const i of [...x, ...y]) { const o = m.get(i.id); if (!o || i.updatedUtc > o.updatedUtc) m.set(i.id, i) }
    return [...m.values()]
  }
  return { bows: pick(a?.bows ?? [], b?.bows ?? []), arrows: pick(a?.arrows ?? [], b?.arrows ?? []) }
}

/** Every bow and arrow set goes up again (things added to an account at sign-in). */
export async function markEquipmentPending() {
  const e = await getEquipment()
  if (e.bows.length || e.arrows.length) await save({ bows: e.bows.map(b => ({ ...b, pending: true })), arrows: e.arrows.map(a => ({ ...a, pending: true })) })
}

const bowJson = (b: Bow) => ({ type: b.type, make: b.make ?? null, model: b.model ?? null, poundageLb: b.poundageLb ?? null,
  bowStyles: b.bowStyles, isDeleted: !!b.deleted, clientUpdatedUtc: b.updatedUtc })
const arrowJson = (a: ArrowSet) => ({ make: a.make ?? null, model: a.model ?? null, lengthIn: a.lengthIn ?? null, arrowGrains: a.arrowGrains ?? null,
  spine: a.spine ?? null, fletchCount: a.fletchCount ?? null, fletchMaterial: a.fletchMaterial ?? null, fletchLengthIn: a.fletchLengthIn ?? null,
  fletchColours: a.fletchColours ?? null, pointGrains: a.pointGrains ?? null, bowIds: a.bowIds, isDeleted: !!a.deleted, clientUpdatedUtc: a.updatedUtc })

/**
 * Sync (from the round sync): changes here go up (bows first), then the account's equipment comes down; the newest
 * edit wins. An older server without equipment: nothing happens.
 */
export async function syncEquipment(token: string, stillSignedIn: () => Promise<boolean>) {
  try {
    const sent = new Map<string, string>()   // id → the edit that went up
    const local = await getEquipment()
    for (const b of local.bows.filter(x => x.pending)) {
      await send(() => request('PUT', `/api/equipment/bows/${b.id}`, bowJson(b), token)); sent.set(b.id, b.updatedUtc)
    }
    for (const a of local.arrows.filter(x => x.pending)) {
      await send(() => request('PUT', `/api/equipment/arrows/${a.id}`, arrowJson(a), token)); sent.set(a.id, a.updatedUtc)
    }
    const all: any = await request('GET', '/api/equipment', undefined, token)
    if (!(await stillSignedIn())) return
    const now = await getEquipment()
    const done = <T extends { id: string; updatedUtc: string; pending?: boolean }>(x: T): T => (sent.get(x.id) === x.updatedUtc ? { ...x, pending: false } : x)
    const bows = new Map(now.bows.map(b => [b.id, done(b)]))
    const arrows = new Map(now.arrows.map(a => [a.id, done(a)]))
    for (const j of all.bows ?? []) {
      const l = bows.get(j.bowId)
      if (l && new Date(l.updatedUtc).getTime() >= new Date(j.clientUpdatedUtc).getTime()) continue
      bows.set(j.bowId, { id: j.bowId, type: j.type, make: j.make ?? null, model: j.model ?? null, poundageLb: j.poundageLb ?? null,
        bowStyles: j.bowStyles ?? [], updatedUtc: j.clientUpdatedUtc, deleted: !!j.isDeleted, pending: false })
    }
    for (const j of all.arrows ?? []) {
      const l = arrows.get(j.arrowId)
      if (l && new Date(l.updatedUtc).getTime() >= new Date(j.clientUpdatedUtc).getTime()) continue
      arrows.set(j.arrowId, { id: j.arrowId, make: j.make ?? null, model: j.model ?? null, lengthIn: j.lengthIn ?? null, arrowGrains: j.arrowGrains ?? null,
        spine: j.spine ?? null, fletchCount: j.fletchCount ?? null, fletchMaterial: j.fletchMaterial ?? null, fletchLengthIn: j.fletchLengthIn ?? null,
        fletchColours: j.fletchColours ?? null, pointGrains: j.pointGrains ?? null, bowIds: j.bowIds ?? [], updatedUtc: j.clientUpdatedUtc,
        deleted: !!j.isDeleted, pending: false })
    }
    await save({ bows: [...bows.values()], arrows: [...arrows.values()] })
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 405)) return   // a server from before 3.2.24
    throw e
  }
}

/** Refused (invalid, or someone else's id): left as it is, and not a reason to stop the sync. */
async function send(f: () => Promise<unknown>) {
  try { await f() } catch (e) {
    if (e instanceof ApiError && e.status >= 400 && e.status < 500 && ![401, 404, 405, 426, 429].includes(e.status)) return
    throw e
  }
}
