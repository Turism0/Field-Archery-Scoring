// Signing in and the archer's profile. Like the app: the token stays on this device, never the password.
// Real name and date of birth stay here and are never sent.
import { allRounds, clearSession, everyRoundEvenDeleted, getKv, hiddenCounts, hideShowing, markShowingPending, removeRounds, setKv, showHidden } from './db'
import { needsAsking, ownRoundCount, signInPlan, signOutLoss, type SignInPlan, type SignOutLoss } from '../domain/deviceData'
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

// ---- Whose rounds are on this device (3.2.02; 3.2.09, Pete: put away, not wiped) ----
// 'dataOwner' = the account the showing rounds belong to (none: nobody was signed in); 'owners' = each account's
// username; 'stash:<id>' = a put-away account's profile and Stats filters.

interface Stash { profile?: Profile; statsFilter?: string | null }

/** Signing out (or the server ending the sign-in): [owner]'s rounds, profile and Stats filters are put away here. */
async function putAway(owner: string) {
  let statsFilter: string | null = null
  try { statsFilter = localStorage.getItem('statsFilter') } catch { /* not kept */ }
  await setKv(`stash:${owner}`, { profile: await getProfile(), statsFilter } satisfies Stash)
  await hideShowing(owner)
  await clearSession()
}

/** Signing in: [owner]'s rounds, profile and Stats filters come back. */
async function bringBack(owner: string) {
  await showHidden(owner)
  const s = await getKv<Stash>(`stash:${owner}`)
  if (s) {
    if (s.profile) await saveProfile(s.profile)
    try { if (s.statsFilter) localStorage.setItem('statsFilter', s.statsFilter) } catch { /* not kept */ }
    await setKv(`stash:${owner}`, null)
  }
}

/**
 * A sign-in waiting for the archer to say what happens to rounds on this device that aren't on the account: scored
 * while nobody was signed in, or an earlier account with the same username (deleted, or the server lost it).
 */
export interface PendingSignIn { result: any; username: string; plan: SignInPlan }
/** After signing in: a recovery code to show once (new account or reset), or a question to ask first. */
export interface SignInStep { recoveryCode: string | null; pending: PendingSignIn | null }

async function begin(r: any): Promise<SignInStep> {
  const owners = (await getKv<Record<string, string>>('owners')) ?? {}
  const counts = await hiddenCounts()
  const plan = signInPlan(await getKv<string>('dataOwner'), r.archerId, r.username, ownRoundCount(await allRounds()),
    [...counts].map(([archerId, rounds]) => ({ archerId, username: owners[archerId] ?? '', rounds })))
  if (plan.hideFirst) await putAway(plan.hideFirst)
  if (needsAsking(plan)) return { recoveryCode: r.recoveryCode ?? null, pending: { result: r, username: r.username, plan } }
  await bringBack(r.archerId)
  await signedIn(r)
  return { recoveryCode: r.recoveryCode ?? null, pending: null }
}

/** The answer to [PendingSignIn]: [keep] adds those rounds to this account (backed up again); otherwise they're removed. */
export async function finishSignIn(p: PendingSignIn, keep: boolean) {
  if (keep) {
    for (const id of p.plan.earlier) {
      await showHidden(id)
      // The earlier account's profile and Stats filters come too (unless this account has its own put away here).
      const s = await getKv<Stash>(`stash:${id}`)
      if (s && !(await getKv<Stash>(`stash:${p.result.archerId}`))) await setKv(`stash:${p.result.archerId}`, s)
    }
    await markShowingPending()   // before this account's own rounds come back, so only these go up again
  } else {
    await removeRounds('showing')
    await removeRounds(p.plan.earlier)
  }
  for (const id of p.plan.earlier) await setKv(`stash:${id}`, null)
  await bringBack(p.result.archerId)
  await signedIn(p.result)
}

/** Cancelled at the question: not signed in here, and the server forgets the sign-in. */
export async function cancelSignIn(p: PendingSignIn) {
  try { await request('POST', '/api/auth/logout', undefined, p.result.token) } catch { /* it expires anyway */ }
}

async function signedIn(r: any) {
  await setKv('dataOwner', r.archerId)
  const owners = (await getKv<Record<string, string>>('owners')) ?? {}
  await setKv('owners', { ...owners, [r.archerId]: r.username })
  await setKv('account', { archerId: r.archerId, username: r.username, token: r.token } satisfies Account)
  try {
    const me = await request('GET', '/api/me', undefined, r.token)
    await setKv('account', { archerId: r.archerId, username: r.username, token: r.token, role: me.role })
  } catch { /* next sync tries again */ }
}

/** Rounds on this device signed in before 3.2.02 belong to that account (and its username is remembered). */
export async function claimDeviceData(a: Account) {
  if (!(await getKv<string>('dataOwner'))) await setKv('dataOwner', a.archerId)
  const owners = (await getKv<Record<string, string>>('owners')) ?? {}
  if (owners[a.archerId] !== a.username) await setKv('owners', { ...owners, [a.archerId]: a.username })
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

/** What removing your rounds at sign-out would lose: the dialog warns first. */
export async function signOutCheck(): Promise<SignOutLoss> { return signOutLoss(await everyRoundEvenDeleted()) }

/**
 * Signs out (3.2.09, Pete): your rounds, profile and Stats filters are put away on this device, hidden from anyone
 * else, and come back when you sign in here again. With [remove], they're deleted from this device instead.
 */
export async function signOut(remove = false) {
  const a = await getAccount()
  if (a) { try { await request('POST', '/api/auth/logout', undefined, a.token) } catch { /* signs out here anyway */ } }
  if (!a) return
  if (remove) {
    await removeRounds('showing')
    await setKv(`stash:${a.archerId}`, null)
    await clearSession()
  } else {
    await putAway(a.archerId)
  }
  await setKv('dataOwner', null)
}

export async function deleteAccount(password: string) {
  const a = await getAccount()
  await request('POST', '/api/me/delete', { password }, a?.token)
  // The rounds left showing here now belong to nobody: the next sign-in asks about them.
  await clearSession()
  await setKv('dataOwner', null)
  if (a) {
    const owners = (await getKv<Record<string, string>>('owners')) ?? {}
    delete owners[a.archerId]
    await setKv('owners', owners)
  }
}

/** 401 from the server: the sign-in has gone (revoked or expired). Like signing out: the rounds are put away. */
export async function signedOutByServer() {
  const a = await getAccount()
  if (a) await putAway(a.archerId)
  await setKv('account', null)
  await setKv('dataOwner', null)
}

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
