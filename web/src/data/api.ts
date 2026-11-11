// The server (same address as this web app), as the Android app's FieldArcheryApi.kt.

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public body?: any) { super(message) }
  /** No signal, or the server isn't answering. */
  get isNetwork() { return this.status === 0 }
}

// Served from the API's own address, so "/api/…" works (local development: vite proxies it).
const BASE = import.meta.env.VITE_API_BASE ?? ''

function defaultMessage(status: number): string {
  if (status === 401) return 'Please sign in again.'
  if (status === 404) return 'Not found.'
  if (status === 429) return 'Too many tries. Wait a minute and try again.'
  if (status >= 500) return 'The server had a problem. Try again later.'
  return 'Something went wrong. Try again.'
}

// ---- is the server answering? (for the Home notice) ----
export type ServerState = 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE'
let serverState: ServerState = 'UNKNOWN'
const serverListeners = new Set<(s: ServerState) => void>()
export const getServerState = () => serverState
export function onServerState(l: (s: ServerState) => void): () => void { serverListeners.add(l); return () => serverListeners.delete(l) }
function setServer(s: ServerState) { if (s !== serverState) { serverState = s; serverListeners.forEach(l => l(s)) } }

/**
 * How long a call may take before it counts as no signal (3.2.04). Weak signal in a wood can leave a request hanging for
 * minutes, which held up every later sync and live group update; the app's own limit is the same.
 */
const TIMEOUT_MS = 20_000

export async function request<T = any>(method: string, path: string, body?: unknown, token?: string | null): Promise<T> {
  const abort = new AbortController()
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS)
  try { return await send<T>(method, path, body, token, abort.signal) } finally { clearTimeout(timer) }
}

async function send<T>(method: string, path: string, body: unknown, token: string | null | undefined, signal: AbortSignal): Promise<T> {
  let res: Response
  let text: string
  try {
    res = await fetch(BASE + path, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      cache: 'no-store',
      signal,
    })
    text = await res.text()
  } catch {
    // Timed out: most likely weak signal, so the server isn't marked as down.
    if (signal.aborted) throw new ApiError(0, 'network', "No answer in time (weak signal?). It'll catch up when the signal's better.")
    if (navigator.onLine) setServer('UNAVAILABLE')
    throw new ApiError(0, navigator.onLine ? 'network' : 'offline',
      navigator.onLine ? "The server isn't available at the moment. Try again later." : "You're offline. It'll catch up when you have signal.")
  }
  if (res.status >= 502 && res.status <= 504) { setServer('UNAVAILABLE'); throw new ApiError(0, 'network', "The server isn't available at the moment. Try again later.") }
  setServer('AVAILABLE')
  let json: any = null
  try { json = text ? JSON.parse(text) : {} } catch { json = null }
  if (res.ok) return (json ?? {}) as T
  throw new ApiError(res.status, json?.error || `http_${res.status}`, json?.message || defaultMessage(res.status), json)
}
