// Everything kept on this device, in IndexedDB: rounds, and small settings (account, profile, sync cursor).
// Safari keeps an installed web app's data; in a plain browser tab it may clear it after a few weeks unused,
// which is why the app suggests "Add to Home Screen" and an account (the server has a copy).
import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import type { Round } from '../domain/round'

interface Schema extends DBSchema {
  rounds: { key: string; value: Round; indexes: { byGroup: string } }
  kv: { key: string; value: unknown }
}

let dbp: Promise<IDBPDatabase<Schema>> | null = null
function db() {
  dbp ??= openDB<Schema>('field-archery', 1, {
    upgrade(d) {
      const rounds = d.createObjectStore('rounds', { keyPath: 'id' })
      rounds.createIndex('byGroup', 'groupId')
      d.createObjectStore('kv')
    },
  })
  return dbp
}

// ---- change notifications (screens re-read when something changes) ----
type Listener = () => void
const listeners = new Set<Listener>()
export function onChange(l: Listener): () => void { listeners.add(l); return () => listeners.delete(l) }
function changed() { listeners.forEach(l => l()) }

// ---- rounds ----
// 3.2.09: rounds put away for an account that's signed out (hiddenOwner) are left out of everything below except
// getRoundEvenDeleted and the "signing in and out" section.
const showing = (r: Round) => !r.hiddenOwner
export async function allRounds(): Promise<Round[]> {
  return (await (await db()).getAll('rounds')).filter(r => !r.deleted && showing(r))
}
export async function getRound(id: string): Promise<Round | undefined> {
  const r = await (await db()).get('rounds', id)
  return r && !r.deleted && showing(r) ? r : undefined
}
/** Even deleted or put away (callers check). */
export async function getRoundEvenDeleted(id: string) { return (await db()).get('rounds', id) }
export async function groupRounds(groupId: string): Promise<Round[]> {
  return (await (await db()).getAllFromIndex('rounds', 'byGroup', groupId)).filter(r => !r.deleted && showing(r))
}
export async function putRound(r: Round, notify = true) {
  await (await db()).put('rounds', r)
  if (notify) changed()
}
export async function putRounds(rs: Round[]) {
  const tx = (await db()).transaction('rounds', 'readwrite')
  await Promise.all([...rs.map(r => tx.store.put(r)), tx.done])
  changed()
}
export async function removeRound(id: string) { await (await db()).delete('rounds', id); changed() }
export async function everyRoundEvenDeleted(): Promise<Round[]> { return (await db()).getAll('rounds').then(rs => rs.filter(showing)) }

// ---- settings ----
export async function getKv<T>(key: string): Promise<T | undefined> { return (await (await db()).get('kv', key)) as T | undefined }
export async function setKv(key: string, value: unknown) {
  const d = await db()
  if (value === undefined || value === null) await d.delete('kv', key)
  else await d.put('kv', value, key)
  changed()
}

// ---- signing in and out (3.2.02; 3.2.09 put away, not wiped) ----

/** Kept through signing out: the device's own settings, whose rounds are where ('dataOwner', 'owners') and put-away accounts' things ('stash:…'). */
const KEPT = (k: string) => ['homeLayout', 'handicapJson', 'handicapFetched', 'dataOwner', 'owners'].includes(k) || k.startsWith('stash:')

/** Ends a session here: everything a sign-in keeps (account, sync state, friends, live groups, profile…) except [KEPT]. */
export async function clearSession() {
  const tx = (await db()).transaction('kv', 'readwrite')
  for (const k of await tx.store.getAllKeys()) if (!KEPT(String(k))) await tx.store.delete(k)
  await tx.done
  try { localStorage.removeItem('statsFilter') } catch { /* nothing kept */ }
  changed()
}

/** Puts every showing round away for [owner] (signed out: hidden until they sign in here again). */
export async function hideShowing(owner: string) { await updateRounds(r => !r.hiddenOwner, r => ({ ...r, hiddenOwner: owner })) }

/** Brings [owner]'s rounds back. */
export async function showHidden(owner: string) { await updateRounds(r => r.hiddenOwner === owner, r => ({ ...r, hiddenOwner: null })) }

/** Every showing round goes up again (rounds added to an account at sign-in). */
export async function markShowingPending() {
  await updateRounds(r => !r.hiddenOwner && !r.deleted && (r.groupRole == null || r.groupRole === 'SCORER'), r => ({ ...r, pending: true, failed: false }))
}

/** Deletes the showing rounds, or [owners]' put-away rounds. */
export async function removeRounds(which: 'showing' | string[]) {
  const tx = (await db()).transaction('rounds', 'readwrite')
  for (const r of await tx.store.getAll()) {
    if (which === 'showing' ? !r.hiddenOwner : !!r.hiddenOwner && which.includes(r.hiddenOwner)) await tx.store.delete(r.id)
  }
  await tx.done
  changed()
}

/** How many of their own rounds each put-away account has here. */
export async function hiddenCounts(): Promise<Map<string, number>> {
  const out = new Map<string, number>()
  for (const r of await (await db()).getAll('rounds')) {
    if (r.hiddenOwner && !r.deleted && (r.groupRole == null || r.groupRole === 'SCORER')) out.set(r.hiddenOwner, (out.get(r.hiddenOwner) ?? 0) + 1)
  }
  return out
}

async function updateRounds(which: (r: Round) => boolean, change: (r: Round) => Round) {
  const tx = (await db()).transaction('rounds', 'readwrite')
  for (const r of await tx.store.getAll()) if (which(r)) await tx.store.put(change(r))
  await tx.done
  changed()
}

/** Ask the browser to keep this site's data (Safari and Chrome may agree once the app is installed). */
export async function askToPersist(): Promise<boolean> {
  try { return (await navigator.storage?.persist?.()) ?? false } catch { return false }
}
