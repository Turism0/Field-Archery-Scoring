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

export async function request<T = any>(method: string, path: string, body?: unknown, token?: string | null): Promise<T> {
  let res: Response
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
    })
  } catch {
    throw new ApiError(0, navigator.onLine ? 'network' : 'offline',
      navigator.onLine ? "The server isn't available at the moment. Try again later." : "You're offline. It'll catch up when you have signal.")
  }
  if (res.status >= 502 && res.status <= 504) throw new ApiError(0, 'network', "The server isn't available at the moment. Try again later.")
  const text = await res.text()
  let json: any = null
  try { json = text ? JSON.parse(text) : {} } catch { json = null }
  if (res.ok) return (json ?? {}) as T
  throw new ApiError(res.status, json?.error || `http_${res.status}`, json?.message || defaultMessage(res.status), json)
}
