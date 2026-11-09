// The handicap model from the server (GET /api/handicap, no sign-in), kept on this device so the calculator and
// handicap points work with no signal. Refreshed at most once a day. Also the Admin → Handicap calls.
import { getKv, setKv } from './db'
import { request } from './api'
import { authed } from './account'
import { modelFromJson, type HandicapModel } from '../domain/handicap'

export async function getModel(): Promise<HandicapModel | null> {
  const j = await getKv<any>('handicapJson')
  try { return j ? modelFromJson(j) : null } catch { return null }
}

/** Downloads it now. False if the server has none yet or can't be reached. */
export async function refreshModel(): Promise<boolean> {
  try {
    const j = await request('GET', '/api/handicap')
    modelFromJson(j)
    await setKv('handicapJson', j)
    await setKv('handicapFetched', Date.now())
    return true
  } catch { return false }
}

export async function refreshModelIfStale() {
  const last = (await getKv<number>('handicapFetched')) ?? 0
  if (await getModel() && Date.now() - last < 86_400_000) return
  await refreshModel()
}

// ---- Admin → Handicap ----
export const adminHandicap = () => authed(t => request('GET', '/api/admin/handicap', undefined, t))
export const setHandicapTargets = (champs: string, year: number, targetsPerDay: number | null) =>
  authed(t => request('PUT', `/api/admin/handicap/events/${champs}/${year}`, { targetsPerDay }, t))
export const requestHandicapFetch = () => authed(t => request('POST', '/api/admin/handicap/fetch', {}, t))
export async function activateHandicapModel(modelId: number) {
  await authed(t => request('POST', `/api/admin/handicap/models/${modelId}/activate`, {}, t))
  await refreshModel()
}
