// Signing in and the archer's profile. Like the app: the token stays on this device, never the password.
// Real name and date of birth stay here and are never sent.
import { getKv, setKv } from './db'
import { ApiError, request } from './api'
import type { AgeGroup, ArcherClass } from '../domain/round'

export interface Account { archerId: string; username: string; token: string; role?: string }
export interface Profile {
  realName?: string
  dateOfBirth?: string        // yyyy-mm-dd
  archerClass?: ArcherClass
  ageGroupManual?: AgeGroup   // null = from date of birth
  bowStyle?: string
}
export interface Friend { archerId: string; username: string }

export const getAccount = () => getKv<Account>('account')
export const getProfile = async () => (await getKv<Profile>('profile')) ?? {}
export const saveProfile = (p: Profile) => setKv('profile', p)
export const cachedFriends = async () => (await getKv<Friend[]>('friends')) ?? []

/** The device name the server lists against this sign-in, e.g. "iPhone (web app)". */
function deviceName(): string {
  const ua = navigator.userAgent
  const what = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android' : 'Browser'
  return `${what} (web app)`
}

async function signedIn(r: any): Promise<string | null> {
  await setKv('account', { archerId: r.archerId, username: r.username, token: r.token } satisfies Account)
  const p = await getProfile()
  await saveProfile({ ...p })
  try {
    const me = await request('GET', '/api/me', undefined, r.token)
    await setKv('account', { archerId: r.archerId, username: r.username, token: r.token, role: me.role })
  } catch { /* next sync tries again */ }
  return r.recoveryCode ?? null
}

export async function checkUsername(username: string): Promise<{ username: string; exists: boolean }> {
  return request('POST', '/api/auth/check', { username: username.trim() })
}

export async function login(username: string, password: string) {
  await signedIn(await request('POST', '/api/auth/login', { username: username.trim(), password, deviceName: deviceName() }))
}

/** Returns the new account's recovery code, to show once. */
export async function register(username: string, password: string): Promise<string | null> {
  return signedIn(await request('POST', '/api/auth/register', { username: username.trim(), password, deviceName: deviceName() }))
}

/** Forgotten password: recovery code or an Admin's reset code. Returns the new recovery code. */
export async function resetPassword(username: string, code: string, newPassword: string): Promise<string | null> {
  return signedIn(await request('POST', '/api/auth/reset', { username: username.trim(), code: code.trim(), newPassword, deviceName: deviceName() }))
}

export async function newRecoveryCode(password: string): Promise<string> {
  const a = await getAccount()
  return (await request('POST', '/api/auth/recovery-code', { password }, a?.token)).recoveryCode
}

export async function signOut() {
  const a = await getAccount()
  if (a) { try { await request('POST', '/api/auth/logout', undefined, a.token) } catch { /* signs out here anyway */ } }
  await setKv('account', null)
  await setKv('cursor', null)
}

export async function deleteAccount(password: string) {
  const a = await getAccount()
  await request('POST', '/api/me/delete', { password }, a?.token)
  await setKv('account', null)
  await setKv('cursor', null)
}

/** 401 from the server: the sign-in has gone (revoked or expired). */
export async function signedOutByServer() { await setKv('account', null) }

/**
 * A call that needs the sign-in. 401 signs out here; not_admin / not_owner correct the role kept here.
 * Throws "Sign in (Profile) first." when not signed in.
 */
export async function authed<T = any>(f: (token: string) => Promise<T>): Promise<T> {
  const a = await getAccount()
  if (!a) throw new ApiError(401, 'not_signed_in', 'Sign in (Profile) first.')
  try { return await f(a.token) } catch (e) {
    if (e instanceof ApiError) {
      if (e.status === 401) await signedOutByServer()
      else if (e.code === 'not_admin') await setKv('account', { ...a, role: 'USER' })
      else if (e.code === 'not_owner') await setKv('account', { ...a, role: 'ADMIN' })
    }
    throw e
  }
}

export const isAdmin = (a?: Account | null) => a?.role === 'ADMIN' || a?.role === 'OWNER'
