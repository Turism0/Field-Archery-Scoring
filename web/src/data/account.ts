// Signing in and the archer's profile. Like the app: the token stays on this device, never the password.
// Real name and date of birth stay here and are never sent.
import { allRounds, clearDevice, everyRoundEvenDeleted, getKv, putRounds, setKv } from './db'
import { ownRoundCount, signInData, signOutLoss, type SignOutLoss } from '../domain/deviceData'
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

/**
 * A sign-in that's waiting for the archer to say what happens to the rounds on this device scored while nobody was
 * signed in (3.2.02): add them to this account, or remove them.
 */
export interface PendingSignIn { result: any; username: string; rounds: number }
/** After signing in: a recovery code to show once (new account or reset), or a question to ask first. */
export interface SignInStep { recoveryCode: string | null; pending: PendingSignIn | null }

/** Rounds already here: this account's stay, another account's go, signed-out ones are asked about. */
async function begin(r: any): Promise<SignInStep> {
  const rounds = ownRoundCount(await allRounds())
  const what = signInData(await getKv<string>('dataOwner'), r.archerId, rounds)
  if (what === 'ASK') return { recoveryCode: r.recoveryCode ?? null, pending: { result: r, username: r.username, rounds } }
  if (what === 'CLEAR') await clearDevice()
  await signedIn(r)
  return { recoveryCode: r.recoveryCode ?? null, pending: null }
}

/** The answer to [PendingSignIn]: [keep] adds the rounds to this account; otherwise they're removed first. */
export async function finishSignIn(p: PendingSignIn, keep: boolean) {
  if (!keep) await clearDevice(true)
  // All of them go up to this account (some may have been backed up to an account since deleted).
  else await putRounds((await allRounds()).filter(r => r.groupRole == null || r.groupRole === 'SCORER').map(r => ({ ...r, pending: true, failed: false })))
  await signedIn(p.result)
}

/** Cancelled at the question: not signed in here, and the server forgets the sign-in. */
export async function cancelSignIn(p: PendingSignIn) {
  try { await request('POST', '/api/auth/logout', undefined, p.result.token) } catch { /* it expires anyway */ }
}

async function signedIn(r: any) {
  await setKv('dataOwner', r.archerId)
  await setKv('account', { archerId: r.archerId, username: r.username, token: r.token } satisfies Account)
  const p = await getProfile()
  await saveProfile({ ...p })
  try {
    const me = await request('GET', '/api/me', undefined, r.token)
    await setKv('account', { archerId: r.archerId, username: r.username, token: r.token, role: me.role })
  } catch { /* next sync tries again */ }
}

/** Rounds on this device signed in before 3.2.02 belong to that account. */
export async function claimDeviceData(a: Account) {
  if (!(await getKv<string>('dataOwner'))) await setKv('dataOwner', a.archerId)
}

export async function checkUsername(username: string): Promise<{ username: string; exists: boolean }> {
  return request('POST', '/api/auth/check', { username: username.trim() })
}

export async function login(username: string, password: string): Promise<SignInStep> {
  return begin(await request('POST', '/api/auth/login', { username: username.trim(), password, deviceName: deviceName() }))
}

/** Creates the account and signs in (its recovery code is in the step, to show once). */
export async function register(username: string, password: string): Promise<SignInStep> {
  return begin(await request('POST', '/api/auth/register', { username: username.trim(), password, deviceName: deviceName() }))
}

/** Forgotten password: recovery code or an Admin's reset code. Signs in (the new recovery code is in the step). */
export async function resetPassword(username: string, code: string, newPassword: string): Promise<SignInStep> {
  return begin(await request('POST', '/api/auth/reset', { username: username.trim(), code: code.trim(), newPassword, deviceName: deviceName() }))
}

export async function newRecoveryCode(password: string): Promise<string> {
  const a = await getAccount()
  return (await request('POST', '/api/auth/recovery-code', { password }, a?.token)).recoveryCode
}

/** What signing out would lose (3.2.02): the dialog warns first. */
export async function signOutCheck(): Promise<SignOutLoss> { return signOutLoss(await everyRoundEvenDeleted()) }

/**
 * Signs out and clears this device (3.2.02, Pete): rounds, profile, friends and the rest, so the next person to sign in
 * starts clean. Your rounds come back from the server when you sign in again.
 */
export async function signOut() {
  const a = await getAccount()
  if (a) { try { await request('POST', '/api/auth/logout', undefined, a.token) } catch { /* signs out here anyway */ } }
  await clearDevice()
}

export async function deleteAccount(password: string) {
  const a = await getAccount()
  await request('POST', '/api/me/delete', { password }, a?.token)
  await setKv('account', null)
  await setKv('cursor', null)
  await setKv('dataOwner', null)   // the rounds left here now belong to nobody: the next sign-in asks about them
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
