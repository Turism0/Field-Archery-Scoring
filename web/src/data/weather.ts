// Round weather on this device (3.2.17): downloaded from GET /api/rounds/weather and kept as one map in kv 'weather'
// (round id → weather), with the download cursor in kv 'weatherCursor'. Both go when the device's session is cleared
// (another account signs in), and are downloaded again. Plus "compared with others" (POST /api/stats/compare).
import { getKv, setKv } from './db'
import { request } from './api'
import { authed } from './account'
import { weatherFromJson, type Weather } from '../domain/weather'
import type { Round } from '../domain/round'

export type WeatherMap = Record<string, Weather>

export async function weatherMap(): Promise<WeatherMap> {
  return (await getKv<WeatherMap>('weather')) ?? {}
}

/** Downloads weather that's changed since last time (best effort: an older server without it is just skipped). */
export async function downloadWeather(token: string, stillSignedIn: () => Promise<boolean>) {
  const cursor = await getKv<{ since: string; afterId: string }>('weatherCursor')
  let since = cursor?.since ?? null, afterId = cursor?.afterId ?? null
  const map = await weatherMap()
  let changed = false
  for (let page = 0; page < 50; page++) {
    const q = [since ? `since=${encodeURIComponent(since)}` : '', afterId ? `afterId=${afterId}` : ''].filter(Boolean).join('&')
    let res: any
    try { res = await request('GET', `/api/rounds/weather${q ? `?${q}` : ''}`, undefined, token) } catch { return }
    for (const x of res.weather ?? []) {
      const w = weatherFromJson(x.weather)
      if (w) map[x.roundId] = w; else delete map[x.roundId]
      since = x.updatedUtc; afterId = x.roundId; changed = true
    }
    if (!res.more) break
  }
  if (!(await stillSignedIn())) return
  if (changed) { await setKv('weather', map); await setKv('weatherCursor', { since, afterId }) }
}

/** Server form of a class: G / L. */
const cls = (r: Round) => (r.archerClass === 'LADIES' ? 'L' : 'G')
export const compareKey = (r: Round) => `${r.type}|${r.bowStyle}|${cls(r)}|${r.ageGroup}`

export interface CompareResult { key: string; rounds: number; archers: number; betterThan: number | null }

/** For each [key] → your average: how many of everyone else's rounds of that kind you beat (null when too few). */
export async function compareWithOthers(groups: { key: string; average: number }[]): Promise<CompareResult[]> {
  if (!groups.length) return []
  const body = { groups: groups.map(g => { const [roundType, bowStyle, archerClass, ageGroup] = g.key.split('|'); return { roundType, bowStyle, archerClass, ageGroup, average: g.average } }) }
  const res = await authed(t => request('POST', '/api/stats/compare', body, t))
  return (res.groups ?? []).map((g: any) => ({
    key: `${g.roundType}|${g.bowStyle}|${g.archerClass}|${g.ageGroup}`, rounds: g.rounds ?? 0, archers: g.archers ?? 0, betterThan: g.betterThan ?? null,
  }))
}
