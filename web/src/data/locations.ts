// Shoot locations (as the app's LocationRepository and LocationPageRepository).
//  - When a round starts, one GPS position is taken and kept on this device with the round.
//  - With signal and an account, the server is asked for a location within its catchment (about a quarter mile):
//    found → the round gets it; none → the archer is asked to name it.
// Your own position is never uploaded with a round; it's only sent (in the request body) to ask what's nearby.
import { request, ApiError } from './api'
import { authed, getAccount } from './account'
import { allRounds, getRound, groupRounds, putRound, putRounds } from './db'
import { AGE_NAMES, CLASS_NAMES, nowUtc, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import { classLineOf, handicapOf, targetsLineOf, type RoundSummary } from './friends'

export interface NearbyLocation { id: string; name: string; distanceMetres: number }
export interface MapLocation { id: string; name: string; latitude: number; longitude: number; isPrivate: boolean; myRounds: number; rounds: number }
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
    for (const r of await allRounds()) {
      if (r.locationState !== 'PENDING_LOOKUP' || r.fixLat == null || r.locationId) continue
      const j = await request('POST', '/api/locations/nearby', { latitude: r.fixLat, longitude: r.fixLon, radiusMetres: null }, a.token)
      const nearest = j.locations?.[0]
      if (nearest) await setLocation(r.id, nearest.locationId, nearest.name)
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
  await putRounds(all.map(x => ({ ...x, locationId, locationName: name, locationState: null, updatedUtc: now, pending: true, failed: false })))
}

/** "Not here?": locations within 10 km of the round's position, nearest first. */
export async function nearby(r: Round): Promise<NearbyLocation[]> {
  if (r.fixLat == null) return []
  const j = await authed(t => request('POST', '/api/locations/nearby', { latitude: r.fixLat, longitude: r.fixLon, radiusMetres: NOT_HERE_RADIUS }, t))
  return (j.locations ?? []).map((l: any) => ({ id: l.locationId, name: l.name, distanceMetres: l.distanceMetres }))
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

/** A new location; 409 name_taken → NameTaken with the existing one. */
export async function createLocation(name: string, lat: number, lon: number, isPrivate: boolean): Promise<NearbyLocation> {
  try {
    const j = await authed(t => request('POST', '/api/locations', { name: name.trim(), latitude: lat, longitude: lon, isPrivate }, t))
    return { id: j.locationId, name: j.name, distanceMetres: 0 }
  } catch (e) {
    const ex = e instanceof ApiError ? e.body?.existing : null
    if (e instanceof ApiError && e.code === 'name_taken' && ex) throw new NameTaken({ id: ex.locationId, name: ex.name, distanceMetres: -1 }, e.message)
    throw e
  }
}

/** Names a new location at a point and gives it to the round. */
export async function nameAt(roundId: string, name: string, lat: number, lon: number, isPrivate: boolean) {
  const l = await createLocation(name, lat, lon, isPrivate)
  await setLocation(roundId, l.id, l.name)
}

// ---- map and location pages (straight from the server) ----

const toMap = (l: any): MapLocation => ({ id: l.locationId, name: l.name, latitude: l.latitude, longitude: l.longitude,
  isPrivate: !!l.isPrivate, myRounds: l.myRounds ?? 0, rounds: l.rounds ?? 0 })

export async function mapLocations(): Promise<MapLocation[]> {
  return (await authed(t => request('GET', '/api/locations/map', undefined, t))).locations.map(toMap)
}

export interface LocationFriend {
  archerId: string; username: string; roundType: string; rounds: number; bestTotal: number | null
  averagePerAnimal: number | null; average36: number | null; average40: number | null; roundsList: RoundSummary[]
}
export interface BowStyleStats {
  roundType: string; bowStyle: string; archers: number; rounds: number; bestTotal: number | null; averageTotal: number | null
  averagePerAnimal: number | null; average36: number | null; average40: number | null
  archerClass: ArcherClass | null; ageGroup: AgeGroup | null
}
export interface LocationPage { location: MapLocation; myRounds: RoundSummary[]; friends: LocationFriend[]; bowStyles: BowStyleStats[]; bowClasses: BowStyleStats[] }

const summary = (r: any): RoundSummary => ({
  roundId: r.roundId, shootDate: String(r.shootDate).slice(0, 10), targetCount: r.targetCount, classLine: classLineOf(r),
  totalPoints: r.totalPoints, locationName: r.locationName ?? null, targetsLine: targetsLineOf(r), handicap: handicapOf(r),
})
const bowStats = (b: any, withClass: boolean): BowStyleStats => ({
  roundType: b.roundType ?? 'BIG_GAME', bowStyle: b.bowStyle, archers: b.archers, rounds: b.rounds, bestTotal: b.bestTotal ?? null,
  averageTotal: b.averageTotal ?? null, averagePerAnimal: b.averagePerAnimal ?? null, average36: b.average36 ?? null, average40: b.average40 ?? null,
  archerClass: withClass ? (b.archerClass === 'L' ? 'LADIES' : 'GENTS') : null, ageGroup: withClass ? b.ageGroup ?? null : null,
})

export async function locationPage(id: string): Promise<LocationPage> {
  const j = await authed(t => request('GET', `/api/locations/${id}/page`, undefined, t))
  return {
    location: toMap(j.location),
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
