// Shoot locations (as the app's LocationRepository and LocationPageRepository).
//  - When a round starts, one GPS position is taken and kept on this device with the round.
//  - With signal and an account, the server is asked for a location within its catchment (about a quarter mile):
//    found → the round gets it; none → the archer is asked to name it.
// Your own position is never uploaded with a round; it's only sent (in the request body) to ask what's nearby.
import { request, ApiError } from './api'
import { authed, getAccount } from './account'
import { allRounds, getRound, groupRounds, putRound, putRounds } from './db'
import { AGE_NAMES, CLASS_NAMES, nowUtc, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import { classLineOf, targetsLineOf, type RoundSummary } from './friends'
import { getKv, setKv } from './db'
import { CATCHMENT_METRES, nearbyPlaces, placeMatches, type KnownPlace, type PlaceMatch } from '../domain/locationMatch'
import type { PendingPlace } from '../domain/round'

// ---- 3.2.25 (Pete): this device's copy of the locations you can use, for no signal ----
// kv 'placeCache': public locations and your own private ones, with or without a pin. Refreshed on each sync and whenever
// the list is fetched; cleared with the account's other things at sign-out (it isn't kept through clearSession).

export const getPlaceCache = async (): Promise<KnownPlace[]> => (await getKv<KnownPlace[]>('placeCache')) ?? []
const toKnown = (l: MapLocation): KnownPlace => ({ id: l.id, name: l.name, latitude: l.latitude, longitude: l.longitude, isPrivate: l.isPrivate })
async function addToCache(p: KnownPlace) { await setKv('placeCache', [...(await getPlaceCache()).filter(x => x.id !== p.id), p]) }

/** Refreshes this device's copy (called on every sync). */
export async function refreshPlaceCache() {
  if (!(await getAccount())) return
  const list = await knownLocations()
  if (await getAccount()) await setKv('placeCache', list.map(toKnown))
}

/** Known locations, and this device's copy refreshed from them; with no signal, the copy. cached: it's the copy. */
export async function knownOrCached(): Promise<{ list: MapLocation[]; cached: boolean }> {
  try {
    const list = await knownLocations()
    await setKv('placeCache', list.map(toKnown))
    return { list, cached: false }
  } catch (e) {
    const c = await getPlaceCache()
    if (!(e instanceof ApiError && e.isNetwork) || !c.length) throw e
    return { list: c.map(p => ({ ...p, myRounds: 0, rounds: 0 })).sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' })), cached: true }
  }
}

/** 3.2.25: locations that might be the one being named (offline: from this device's copy). */
export class SimilarExists extends Error {
  constructor(public matches: PlaceMatch[], public offline: boolean) { super("This might be a location that's already known.") }
}

const matchFrom = (m: any): PlaceMatch => ({ id: m.locationId, name: m.name, isPrivate: !!m.isPrivate, distanceMetres: m.distanceMetres ?? null, reasons: m.reasons ?? [] })

/** Sends a new location: check asks about similar ones first (skip: already turned down). The made location. */
async function send(p: PendingPlace, check: boolean): Promise<KnownPlace> {
  let j: any
  try {
    j = await authed(t => request('POST', '/api/locations', p.noPin
      ? { name: p.name, noPin: true, isPrivate: p.isPrivate, ...(check ? { checkSimilar: true } : {}) }
      : { name: p.name, latitude: p.latitude, longitude: p.longitude, isPrivate: p.isPrivate, ...(check ? { checkSimilar: true } : {}) }, t))
  } catch (e) {
    if (e instanceof ApiError && e.code === 'name_taken' && e.body?.existing) {
      throw new NameTaken({ id: e.body.existing.locationId, name: e.body.existing.name, distanceMetres: -1 }, e.message)
    }
    if (e instanceof ApiError && e.code === 'similar_exists') {
      const left = ((e.body?.matches ?? []) as any[]).map(matchFrom).filter(m => !p.skip.includes(m.id))
      if (!left.length) return send(p, false)   // only ones already turned down: go ahead
      throw new SimilarExists(left, false)
    }
    throw e
  }
  const made: KnownPlace = { id: j.locationId, name: j.name, latitude: p.latitude, longitude: p.longitude, isPrivate: p.isPrivate }
  await addToCache(made)
  return made
}

/** Makes a new location and gives it to the round; with no signal, checks this device's copy then keeps it on the round. */
async function createFor(roundId: string, p: PendingPlace, check: boolean) {
  let made: KnownPlace
  try { made = await send(p, check) } catch (e) {
    if (!(e instanceof ApiError && e.isNetwork)) throw e
    if (check) {
      const local = placeMatches(p.name, p.latitude, p.longitude, await getPlaceCache(), p.skip)
      if (local.length) throw new SimilarExists(local, true)
    }
    const r = await getRound(roundId)
    if (r) await putRound({ ...r, pendingLocation: { ...p, matches: [] }, locationState: 'PENDING_NAME' })
    return
  }
  await setLocation(roundId, made.id, made.name)
}

/** Locations named with no signal are made now (on each sync); possible duplicates wait for the archer (CHECK_NAME). */
export async function createPending() {
  if (!(await getAccount())) return
  for (const r of await allRounds()) {
    if (r.locationState !== 'PENDING_NAME' || !r.pendingLocation || r.locationId) continue
    const p = r.pendingLocation
    try {
      const made = await send(p, true)
      await setLocation(r.id, made.id, made.name)
    } catch (e) {
      if (e instanceof SimilarExists) await putRound({ ...r, pendingLocation: { ...p, matches: e.matches }, locationState: 'CHECK_NAME' })
      else if (e instanceof NameTaken) await putRound({ ...r, pendingLocation: { ...p, matches: [{ id: e.existing.id, name: e.existing.name, isPrivate: false, distanceMetres: null, reasons: ['same_name'] }] }, locationState: 'CHECK_NAME' })
      else if (e instanceof ApiError && !e.isNetwork && e.status !== 401 && e.status !== 429 && e.status < 500) await putRound({ ...r, pendingLocation: null, locationState: 'NEEDS_NAME' })
      else throw e
    }
  }
}

/** The archer's answer for a location named with no signal: use one of the suggestions, or null to keep their own name. */
export async function settlePending(roundId: string, use: PlaceMatch | null) {
  const r = await getRound(roundId)
  const p = r?.pendingLocation
  if (!r || !p) return
  if (use) { await setLocation(roundId, use.id, use.name); return }
  await putRound({ ...r, pendingLocation: { ...p, skip: [...p.skip, ...p.matches.map(m => m.id)], matches: [] }, locationState: 'PENDING_NAME' })
  try { await createPending() } catch { /* next sync */ }
}

export interface NearbyLocation { id: string; name: string; distanceMetres: number }
/** latitude / longitude null for a name-only location (3.0: no map pin, e.g. a Nationals years ago). */
export interface MapLocation {
  id: string; name: string; latitude: number | null; longitude: number | null; isPrivate: boolean; myRounds: number; rounds: number
  /** 3.2.31 (Pete): someone else's private location (only ever sent to the Owner): on the map in its own colour, never offered or cached. */
  othersPrivate?: boolean
  /** 3.2.33 (Pete): your private location has a similar public one you haven't merged into or said is a different place. */
  checkSimilar?: boolean
}
/** A location on the map (it has a pin). */
export type PinnedLocation = MapLocation & { latitude: number; longitude: number }
export const hasPin = (l: MapLocation): l is PinnedLocation => l.latitude != null && l.longitude != null
export const NOT_HERE_RADIUS = 10_000

/** One position, or null (refused, unavailable or timed out). */
export function currentPosition(timeoutMs = 30_000): Promise<{ lat: number; lon: number } | null> {
  return new Promise(resolve => {
    if (!navigator.geolocation) return resolve(null)
    navigator.geolocation.getCurrentPosition(
      p => resolve({ lat: p.coords.latitude, lon: p.coords.longitude }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: timeoutMs, maximumAge: 120_000 },
    )
  })
}

/** Takes a position for a new round (in the background), then looks it up if possible. */
export async function captureFor(roundId: string): Promise<boolean> {
  const fix = await currentPosition()
  if (!fix) return false
  const r = await getRound(roundId)
  if (!r || r.locationId) return false
  // In a group everyone is at the same shoot.
  const all = r.groupId ? await groupRounds(r.groupId) : [r]
  await putRounds(all.filter(x => !x.locationId).map(x => ({ ...x, fixLat: fix.lat, fixLon: fix.lon, locationState: 'PENDING_LOOKUP' as const })))
  try { await lookUpPending() } catch { /* next sync */ }
  return true
}

let looking: Promise<void> | null = null
/** Looks up every round whose position hasn't been matched yet. Needs an account and signal. */
export function lookUpPending(): Promise<void> {
  looking ??= (async () => {
    const a = await getAccount()
    if (!a) return
    let offline = false
    for (const r of await allRounds()) {
      if (r.locationState !== 'PENDING_LOOKUP' || r.fixLat == null || r.locationId) continue
      if (!offline) {
        try {
          const j = await request('POST', '/api/locations/nearby', { latitude: r.fixLat, longitude: r.fixLon, radiusMetres: null }, a.token)
          const nearest = j.locations?.[0]
          if (nearest) await setLocation(r.id, nearest.locationId, nearest.name)
          else await putRound({ ...r, locationState: 'NEEDS_NAME' })
          continue
        } catch (e) {
          if (!(e instanceof ApiError && e.isNetwork)) throw e
          offline = true
        }
      }
      // 3.2.25: no signal: this device's copy (nothing there yet: wait for signal).
      const known = await getPlaceCache()
      if (!known.length) continue
      const nearest = nearbyPlaces(known, r.fixLat, r.fixLon!, CATCHMENT_METRES)[0]
      if (nearest) await setLocation(r.id, nearest.id, nearest.name)
      else await putRound({ ...r, locationState: 'NEEDS_NAME' })
    }
  })().finally(() => { looking = null })
  return looking
}

/** Picks up renamed and merged locations for every round on this device (not an edit). */
export async function refreshNames() {
  const a = await getAccount()
  if (!a) return
  const rounds = await allRounds()
  const ids = [...new Set(rounds.map(r => r.locationId).filter((x): x is string => !!x))]
  if (!ids.length) return
  const j = await request('POST', '/api/locations/resolve', { ids }, a.token)
  const changed: Round[] = []
  for (const l of j.locations ?? []) {
    for (const r of rounds) {
      if (r.locationId === l.requestedId && (r.locationId !== l.locationId || r.locationName !== l.name)) {
        changed.push({ ...r, locationId: l.locationId, locationName: l.name })
      }
    }
  }
  if (changed.length) await putRounds(changed)
}

/** Gives the round (and everyone in its group) a location, as an edit so it goes up to the server again. */
export async function setLocation(roundId: string, locationId: string, name: string) {
  const r = await getRound(roundId)
  if (!r) return
  const all = r.groupId ? await groupRounds(r.groupId) : [r]
  const now = nowUtc()
  await putRounds(all.map(x => ({ ...x, locationId, locationName: name, locationState: null, pendingLocation: null, updatedUtc: now, pending: true, failed: false })))
}

/** "Not here?": locations within 10 km of the round's position, nearest first. */
export async function nearby(r: Round): Promise<NearbyLocation[]> { return (await nearbyOrCached(r)).list }

/** The same; with no signal (3.2.25), from this device's copy (cached). */
export async function nearbyOrCached(r: Round): Promise<{ list: NearbyLocation[]; cached: boolean }> {
  if (r.fixLat == null) return { list: [], cached: false }
  try {
    const j = await authed(t => request('POST', '/api/locations/nearby', { latitude: r.fixLat, longitude: r.fixLon, radiusMetres: NOT_HERE_RADIUS }, t))
    return { list: (j.locations ?? []).map((l: any) => ({ id: l.locationId, name: l.name, distanceMetres: l.distanceMetres })), cached: false }
  } catch (e) {
    const known = await getPlaceCache()
    if (!(e instanceof ApiError && e.isNetwork) || !known.length) throw e
    return { list: nearbyPlaces(known, r.fixLat, r.fixLon!, NOT_HERE_RADIUS), cached: true }
  }
}

/** Took a position now for a round with none. */
export async function captureNow(roundId: string): Promise<boolean> {
  const fix = await currentPosition()
  if (!fix) return false
  const r = await getRound(roundId)
  if (!r) return false
  await putRound({ ...r, fixLat: fix.lat, fixLon: fix.lon, locationState: r.locationId ? r.locationState : 'PENDING_LOOKUP' })
  try { await lookUpPending() } catch { /* next sync */ }
  return true
}

export class NameTaken extends Error {
  constructor(public existing: NearbyLocation, message: string) { super(message) }
}

/** A new location (not for a round); 409 name_taken → NameTaken; check (3.2.25): similar ones → SimilarExists. */
export async function createLocation(name: string, lat: number, lon: number, isPrivate: boolean, check = true, skip: string[] = []): Promise<NearbyLocation> {
  const l = await send({ name: name.trim(), isPrivate, noPin: false, latitude: lat, longitude: lon, skip, matches: [] }, check)
  return { id: l.id, name: l.name, distanceMetres: 0 }
}

/** A new name-only location (3.0): no map pin; NameTaken / SimilarExists as createLocation. */
export async function createNamed(name: string, isPrivate: boolean, check = true, skip: string[] = []): Promise<NearbyLocation> {
  const l = await send({ name: name.trim(), isPrivate, noPin: true, latitude: null, longitude: null, skip, matches: [] }, check)
  return { id: l.id, name: l.name, distanceMetres: -1 }
}

/** Names a new name-only location and gives it to the round (no signal: kept for later, 3.2.25). */
export async function nameOnly(roundId: string, name: string, isPrivate: boolean, check = true, skip: string[] = []) {
  await createFor(roundId, { name: name.trim(), isPrivate, noPin: true, latitude: null, longitude: null, skip, matches: [] }, check)
}

/** Puts a name-only location on the map (its creator or an Admin); [move] (3.2.14): moves one already on it (Admins). */
export async function placeLocation(id: string, lat: number, lon: number, move = false) {
  await authed(t => request('PUT', `/api/locations/${id}/position`, { latitude: lat, longitude: lon, ...(move ? { move: true } : {}) }, t))
}

/**
 * Makes a location you named private or public (3.2.12). Private when others have shot there needs confirm (the page warns
 * first; 409 others_shot_here otherwise); public can be 409 name_taken. Your own rounds keep it either way.
 */
export async function setPrivate(id: string, isPrivate: boolean, confirm = false) {
  await authed(t => request('PUT', `/api/locations/${id}/private`, { isPrivate, confirm }, t))
}

/** Deletes a location (3.2.16): the archer who named it, or the Owner. Rounds there lose it (anyone's). */
/** 3.2.32 (Pete): public locations similar to your private one, to merge it into. */
export async function mergeTargets(id: string): Promise<PlaceMatch[]> {
  return ((await authed(t => request('GET', `/api/locations/${id}/merge-targets`, undefined, t))).locations ?? []).map(matchFrom)
}

/** 3.2.32: merges your private location into a similar public one (its rounds move there); this device's rounds follow. */
export async function mergeInto(id: string, intoId: string): Promise<string> {
  const j = await authed(t => request('POST', `/api/locations/${id}/merge-into`, { intoId }, t))
  try { await refreshNames() } catch { /* the next sync does it */ }
  await refreshSimilarCount()
  return j.name ?? ''
}

/** 3.2.33 (Pete): you say these public locations aren't the same place as your private one, so they stop being suggested. */
export async function notSame(id: string, publicIds: string[]) {
  await authed(t => request('POST', `/api/locations/${id}/not-same`, { publicIds }, t))
  await refreshSimilarCount()
}

/** 3.2.33: the Locations badge straight away (every sync refreshes it too). */
async function refreshSimilarCount() {
  try {
    const me = await authed(t => request('GET', '/api/me', undefined, t))
    if (me.similarLocations != null) await setKv('similarLocationCount', me.similarLocations)
  } catch { /* the next sync does it */ }
}

export async function deleteLocation(id: string) {
  await authed(t => request('DELETE', `/api/locations/${id}`, undefined, t))
}

/** Names a new location at a point and gives it to the round (no signal: kept for later, 3.2.25). */
export async function nameAt(roundId: string, name: string, lat: number, lon: number, isPrivate: boolean, check = true, skip: string[] = []) {
  await createFor(roundId, { name: name.trim(), isPrivate, noPin: false, latitude: lat, longitude: lon, skip, matches: [] }, check)
}

// ---- map and location pages (straight from the server) ----

const toMap = (l: any): MapLocation => ({ id: l.locationId, name: l.name, latitude: l.latitude ?? null, longitude: l.longitude ?? null,
  isPrivate: !!l.isPrivate, myRounds: l.myRounds ?? 0, rounds: l.rounds ?? 0, othersPrivate: !!l.othersPrivate, checkSimilar: !!l.checkSimilar })

/** The locations on the map (with a pin). */
export async function mapLocations(): Promise<PinnedLocation[]> {
  return (await authed(t => request('GET', '/api/locations/map', undefined, t))).locations.map(toMap).filter(hasPin)
}

/** Name-only locations (3.0): no map pin. An older server without them gives an empty list. */
export async function namedLocations(): Promise<MapLocation[]> {
  try {
    return (await authed(t => request('GET', '/api/locations/named', undefined, t))).locations.map(toMap)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return []
    throw e
  }
}

/** Every location you can pick for a round, by name: on the map and name-only. */
export async function knownLocations(): Promise<MapLocation[]> {
  const [pinned, named] = await Promise.all([mapLocations(), namedLocations()])
  // 3.2.31: others' private locations (the Owner's map only) are never offered for a round or kept in this device's copy.
  return [...pinned.filter(l => !l.othersPrivate), ...named].sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }))
}

/** "Name (private)", "Name (no map pin)" for a pick list. */
export const knownLabel = (l: MapLocation) =>
  l.name + [l.isPrivate ? 'private' : null, hasPin(l) ? null : 'no map pin'].filter(Boolean).map(x => ` (${x})`).join('')

export interface LocationFriend {
  archerId: string; username: string; roundType: string; rounds: number; bestTotal: number | null
  averagePerAnimal: number | null; average36: number | null; average40: number | null; roundsList: RoundSummary[]
}
export interface BowStyleStats {
  roundType: string; bowStyle: string; archers: number; rounds: number; bestTotal: number | null; averageTotal: number | null
  averagePerAnimal: number | null; average36: number | null; average40: number | null
  archerClass: ArcherClass | null; ageGroup: AgeGroup | null
}
export interface LocationPage { location: MapLocation & { canPlace: boolean; canMove: boolean; canDelete: boolean; roundsHere: number; canChangePrivacy: boolean; canMergeIntoPublic: boolean; similarPublic: PlaceMatch[]; otherArchers: number; otherRounds: number }; myRounds: RoundSummary[]; friends: LocationFriend[]; bowStyles: BowStyleStats[]; bowClasses: BowStyleStats[] }

const summary = (r: any): RoundSummary => ({
  roundId: r.roundId, shootDate: String(r.shootDate).slice(0, 10), targetCount: r.targetCount, classLine: classLineOf(r),
  totalPoints: r.totalPoints, locationName: r.locationName ?? null, targetsLine: targetsLineOf(r),
})
const bowStats = (b: any, withClass: boolean): BowStyleStats => ({
  roundType: b.roundType ?? 'BIG_GAME', bowStyle: b.bowStyle, archers: b.archers, rounds: b.rounds, bestTotal: b.bestTotal ?? null,
  averageTotal: b.averageTotal ?? null, averagePerAnimal: b.averagePerAnimal ?? null, average36: b.average36 ?? null, average40: b.average40 ?? null,
  archerClass: withClass ? (b.archerClass === 'L' ? 'LADIES' : 'GENTS') : null, ageGroup: withClass ? b.ageGroup ?? null : null,
})

export async function locationPage(id: string): Promise<LocationPage> {
  const j = await authed(t => request('GET', `/api/locations/${id}/page`, undefined, t))
  return {
    location: { ...toMap(j.location), canPlace: !!j.location.canPlace, canMove: !!j.location.canMove, canDelete: !!j.location.canDelete, roundsHere: j.location.roundsHere ?? 0, canChangePrivacy: !!j.location.canChangePrivacy,
      canMergeIntoPublic: !!j.location.canMergeIntoPublic,
      similarPublic: (j.location.similarPublic ?? []).map(matchFrom),
      otherArchers: j.location.otherArchers ?? 0, otherRounds: j.location.otherRounds ?? 0 },
    myRounds: (j.myRounds ?? []).map(summary),
    friends: (j.friends ?? []).map((f: any) => ({
      archerId: f.archerId, username: f.username, roundType: f.roundType ?? 'BIG_GAME', rounds: f.rounds, bestTotal: f.bestTotal ?? null,
      averagePerAnimal: f.averagePerAnimal ?? null, average36: f.average36 ?? null, average40: f.average40 ?? null,
      roundsList: (f.roundsList ?? []).map(summary),
    })),
    bowStyles: (j.bowStyles ?? []).map((b: any) => bowStats(b, false)),
    bowClasses: (j.bowClasses ?? []).map((b: any) => bowStats(b, true)),
  }
}

export const classChoiceLabel = (c: ArcherClass, a: AgeGroup) => (a === 'ADULT' ? CLASS_NAMES[c] : `${AGE_NAMES[a]} ${CLASS_NAMES[c]}`)

export const distanceText = (m: number) => (m < 1000 ? `${m} m away` : `${(m / 1000).toFixed(1)} km away`)
