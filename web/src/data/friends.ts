// Friends and rounds scored for you, straight from the server each time (as the app's FriendsRepository).
import { request } from './api'
import { authed, type Friend } from './account'
import { setKv } from './db'
import { syncNow } from './rounds'
import { AGE_NAMES, CLASS_NAMES, roundFromJson, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import type { HandicapRound } from '../domain/handicap'

export interface FriendSummary {
  archerId: string; username: string; rounds: number; bestTotal: number | null; lastShootDate: string | null
  averagePerAnimal: number | null; woodsmanRounds: number; woodsmanBest: number | null; recent: HandicapRound[]
}
export interface FriendsOverview { friends: FriendSummary[]; incoming: Friend[]; outgoing: Friend[] }
export interface RoundSummary {
  roundId: string; shootDate: string; targetCount: number; classLine: string; totalPoints: number
  locationName: string | null; targetsLine: string; handicap: HandicapRound | null
}
export interface RoundOffer {
  roundId: string; fromUsername: string; shootDate: string; targetCount: number; classLine: string; totalPoints: number
  locationName: string | null
}

const cls = (c: string): ArcherClass => (c === 'L' ? 'LADIES' : 'GENTS')
const classLineOf = (r: any) =>
  `${r.roundType === 'WOODSMAN' ? 'Woodsman · ' : ''}${CLASS_NAMES[cls(r.archerClass)]} ${AGE_NAMES[r.ageGroup as AgeGroup] ?? r.ageGroup} ${r.bowStyle}`

function handicapOf(r: any): HandicapRound | null {
  if (r.roundType === 'WOODSMAN' || !r.bowStyle || !r.ageGroup) return null
  const animals = r.animalCount > 0 ? r.animalCount : r.targetCount
  return { shootDate: String(r.shootDate).slice(0, 10), points: r.totalPoints, maxPoints: 24 * animals,
    archer: { bowStyle: r.bowStyle, archerClass: cls(r.archerClass), ageGroup: r.ageGroup } }
}

function targetsLineOf(r: any): string {
  const n = r.targetCount
  const base = `${n} target${n === 1 ? '' : 's'}`
  if (r.roundType === 'WOODSMAN' || r.animalCount == null || (!r.predatorPrey && !r.fans)) return base
  const extras = [r.predatorPrey ? `${r.predatorPrey} predator/prey` : '', r.fans ? `${r.fans} fan${r.fans === 1 ? '' : 's'}` : ''].filter(Boolean).join(', ')
  return `${base} · ${r.animalCount} animals (${extras})`
}

export async function overview(): Promise<FriendsOverview> {
  const j = await authed(t => request('GET', '/api/friends', undefined, t))
  const o: FriendsOverview = {
    friends: j.friends.map((f: any) => ({
      archerId: f.archerId, username: f.username, rounds: f.rounds ?? 0, bestTotal: f.bestTotal ?? null,
      lastShootDate: f.lastShootDate ? String(f.lastShootDate).slice(0, 10) : null, averagePerAnimal: f.averagePerAnimal ?? null,
      woodsmanRounds: f.woodsmanRounds ?? 0, woodsmanBest: f.woodsmanBest ?? null,
      recent: (f.recent ?? []).map(handicapOf).filter(Boolean),
    })),
    incoming: j.incoming.map((x: any) => ({ archerId: x.archerId, username: x.username })),
    outgoing: j.outgoing.map((x: any) => ({ archerId: x.archerId, username: x.username })),
  }
  await setKv('friends', o.friends.map(f => ({ archerId: f.archerId, username: f.username })))
  return o
}

export async function offers(): Promise<RoundOffer[]> {
  const j = await authed(t => request('GET', '/api/offers', undefined, t))
  const list: RoundOffer[] = j.received.map((o: any) => ({
    roundId: o.roundId, fromUsername: o.fromUsername, shootDate: String(o.shootDate).slice(0, 10), targetCount: o.targetCount,
    classLine: classLineOf(o), totalPoints: o.totalPoints, locationName: o.locationName ?? null,
  }))
  await setKv('offerCount', list.length)
  return list
}

export async function offerRound(roundId: string): Promise<{ round: Round; from: string }> {
  const j = await authed(t => request('GET', `/api/offers/${roundId}`, undefined, t))
  const r = { ...j.round, roundId, isDeleted: false, serverUpdatedUtc: j.offer?.updatedUtc }
  if (r.locationName == null && j.offer?.locationName) r.locationName = j.offer.locationName
  return { round: roundFromJson(r), from: j.offer?.fromUsername ?? '' }
}

export async function acceptOffer(roundId: string) {
  await authed(t => request('POST', `/api/offers/${roundId}/accept`, undefined, t))
  syncNow()
}
export const declineOffer = (roundId: string) => authed(t => request('POST', `/api/offers/${roundId}/decline`, undefined, t))

export async function sendRequest(username: string): Promise<string> {
  const j = await authed(t => request('POST', '/api/friends/requests', { username: username.trim() }, t))
  return j.message ?? 'Friend request sent.'
}
export const acceptFriend = (id: string) => authed(t => request('POST', `/api/friends/${id}/accept`, undefined, t))
/** Decline, cancel or unfriend. */
export const removeFriend = (id: string) => authed(t => request('DELETE', `/api/friends/${id}`, undefined, t))

export async function friendRounds(id: string): Promise<RoundSummary[]> {
  const j = await authed(t => request('GET', `/api/friends/${id}/rounds`, undefined, t))
  return j.rounds.map((r: any) => ({
    roundId: r.roundId, shootDate: String(r.shootDate).slice(0, 10), targetCount: r.targetCount, classLine: classLineOf(r),
    totalPoints: r.totalPoints, locationName: r.locationName ?? null, targetsLine: targetsLineOf(r), handicap: handicapOf(r),
  }))
}

export async function friendRound(id: string, roundId: string): Promise<Round> {
  return roundFromJson(await authed(t => request('GET', `/api/friends/${id}/rounds/${roundId}`, undefined, t)))
}

export { targetsLineOf, handicapOf, classLineOf }
