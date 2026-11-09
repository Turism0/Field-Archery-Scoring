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
export async function allRounds(): Promise<Round[]> {
  return (await (await db()).getAll('rounds')).filter(r => !r.deleted)
}
export async function getRound(id: string): Promise<Round | undefined> {
  const r = await (await db()).get('rounds', id)
  return r && !r.deleted ? r : undefined
}
export async function getRoundEvenDeleted(id: string) { return (await db()).get('rounds', id) }
export async function groupRounds(groupId: string): Promise<Round[]> {
  return (await (await db()).getAllFromIndex('rounds', 'byGroup', groupId)).filter(r => !r.deleted)
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
export async function everyRoundEvenDeleted(): Promise<Round[]> { return (await db()).getAll('rounds') }

// ---- settings ----
export async function getKv<T>(key: string): Promise<T | undefined> { return (await (await db()).get('kv', key)) as T | undefined }
export async function setKv(key: string, value: unknown) {
  const d = await db()
  if (value === undefined || value === null) await d.delete('kv', key)
  else await d.put('kv', value, key)
  changed()
}

/** Ask the browser to keep this site's data (Safari and Chrome may agree once the app is installed). */
export async function askToPersist(): Promise<boolean> {
  try { return (await navigator.storage?.persist?.()) ?? false } catch { return false }
}
