// Admin, straight from the server (as the app's AdminRepository). The server checks the role on every call.
import { request } from './api'
import { authed } from './account'
import { setKv } from './db'

export interface AdminArcher { archerId: string; username: string; role: string; createdUtc: string | null; lastLoginUtc: string | null }
export interface AdminLocation {
  /** latitude / longitude null: a name-only location (3.0, no map pin), or someone's hidden private one. */
  id: string; name: string; latitude: number | null; longitude: number | null; createdUtc: string | null; createdBy: string | null
  rounds: number; isPrivate: boolean; hidden: boolean
}
/** distanceMetres null when either has no map pin (3.0): paired on their names. */
export interface DuplicatePair { a: AdminLocation; b: AdminLocation; distanceMetres: number | null; reasons: string[] }

const toLoc = (l: any): AdminLocation => ({
  id: l.locationId, name: l.name, latitude: l.latitude ?? null, longitude: l.longitude ?? null, createdUtc: l.createdUtc ?? null,
  createdBy: l.createdBy ?? null, rounds: l.rounds ?? 0, isPrivate: !!l.isPrivate, hidden: !!l.hidden,
})

export async function archers(): Promise<AdminArcher[]> {
  return (await authed(t => request('GET', '/api/admin/archers', undefined, t))).archers.map((a: any) => ({
    archerId: a.archerId, username: a.username, role: a.role, createdUtc: a.createdUtc ?? null, lastLoginUtc: a.lastLoginUtc ?? null,
  }))
}
/** Owner only. */
export const setAdmin = (id: string, admin: boolean) => authed(t => request('PUT', `/api/admin/archers/${id}/role`, { role: admin ? 'ADMIN' : 'USER' }, t))
/** A one-off password reset code (24 hours). */
export const resetCode = (id: string): Promise<{ username: string; code: string; expiresUtc: string | null }> =>
  authed(t => request('POST', `/api/admin/archers/${id}/reset-code`, {}, t))

export async function locations(): Promise<AdminLocation[]> {
  return (await authed(t => request('GET', '/api/admin/locations', undefined, t))).locations.map(toLoc)
}
export async function duplicates(): Promise<DuplicatePair[]> {
  const list: DuplicatePair[] = (await authed(t => request('GET', '/api/admin/locations/duplicates', undefined, t))).pairs.map((p: any) => ({
    a: toLoc(p.a), b: toLoc(p.b), distanceMetres: p.distanceMetres ?? null, reasons: p.reasons ?? [],
  }))
  await setKv('duplicateLocations', list.length)
  return list
}
export const approve = (p: DuplicatePair) => authed(t => request('POST', '/api/admin/locations/duplicates/approve', { aId: p.a.id, bId: p.b.id }, t))
export const rename = (id: string, name: string, isPrivate?: boolean) =>
  authed(t => request('PUT', `/api/admin/locations/${id}`, { name: name.trim(), ...(isPrivate != null ? { isPrivate } : {}) }, t))
/** Returns how many rounds moved. */
export async function merge(id: string, intoId: string): Promise<number> {
  return (await authed(t => request('POST', `/api/admin/locations/${id}/merge`, { intoId }, t))).roundsMoved ?? 0
}
