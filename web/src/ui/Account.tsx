import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Select, Seg, back, errorText, go, toast, useData } from './common'
import {
  cancelSignIn, checkUsername, deleteAccount, finishSignIn, getAccount, getProfile, login, newRecoveryCode, register, resetPassword,
  saveProfile, signOut, signOutCheck, type PendingSignIn, type Profile, type SignInStep,
} from '../data/account'
import { syncNow } from '../data/rounds'
import { signInQuestion, signOutWarning } from '../domain/deviceData'
import { AGE_NAMES, BOW_STYLES, CLASS_NAMES, ageGroupFor, type AgeGroup, type ArcherClass } from '../domain/round'
import { today } from './common'

const USERNAME = /^[A-Za-z0-9_.-]{3,30}$/

/** A recovery code to write down: shown once (after making an account, a reset, or from Profile). */
export function RecoveryDialog({ code, onDone }: { code: string | null; onDone: () => void }) {
  const [saved, setSaved] = useState(false)
  return (
    <Dialog open={!!code} title="Your recovery code" actions={<button disabled={!saved} onClick={onDone}>Done</button>}>
      <p>If you forget your password, this code lets you set a new one. Write it down or take a screenshot and keep it safe:
        it can't be shown again. It works once (you get a new one then).</p>
      <p class="code" style="user-select:all">{code}</p>
      <button class="tonal" onClick={() => { navigator.clipboard?.writeText(code ?? ''); toast('Copied.') }}>Copy</button>
      <label class="row" style="margin-top:12px"><input type="checkbox" style="width:auto" checked={saved} onChange={e => setSaved((e.target as HTMLInputElement).checked)} /> I've saved it</label>
    </Dialog>
  )
}

/** Sign in or create an account: username first (the server says if it exists), then the password. */
export function AccountScreen() {
  const [step, setStep] = useState<'name' | 'existing' | 'new' | 'reset'>('name')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [recovery, setRecovery] = useState<string | null>(null)
  const [pending, setPending] = useState<SignInStep | null>(null)

  async function run(f: () => Promise<void>) {
    setBusy(true); setError(null)
    try { await f() } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }
  const done = () => { syncNow(); go('/') }
  /** Signed in, or a question first about rounds here that aren't on the account (3.2.02; 3.2.09). */
  const signedIn = (s: SignInStep) => { if (s.pending) setPending(s); else if (s.recoveryCode) setRecovery(s.recoveryCode); else done() }
  async function answer(keep: boolean | null) {
    const s = pending!, p = s.pending as PendingSignIn
    setPending(null)
    if (keep === null) { await cancelSignIn(p); return }
    await run(async () => { await finishSignIn(p, keep); signedIn({ ...s, pending: null }) })
  }
  const plan = pending?.pending?.plan
  const n = plan ? plan.signedOut + plan.earlierRounds : 0, them = n === 1 ? 'it' : 'them'
  const newPasswordProblem = () =>
    password.length < 8 ? 'Passwords are at least 8 characters.' : password !== confirm ? "The two passwords don't match." : null

  return (
    <>
      <Bar title={step === 'new' ? 'Create account' : step === 'reset' ? 'New password' : 'Sign in'}
        onBack={step === 'name' ? undefined : () => { setStep('name'); setPassword(''); setConfirm('') }} />
      <main class="form">
        {step === 'name' && (
          <Card title="Username" sub="Enter your username to sign in, or choose one to create an account. No email needed.">
            <Field label="Username" help="3 to 30 letters, numbers, dots, dashes or underscores">
              <input autocapitalize="off" autocomplete="username" value={username} onInput={e => setUsername((e.target as HTMLInputElement).value.trim())} />
            </Field>
            <button class="filled wide" disabled={busy} onClick={() => USERNAME.test(username)
              ? run(async () => { const c = await checkUsername(username); setUsername(c.username); setStep(c.exists ? 'existing' : 'new') })
              : setError('Usernames are 3 to 30 characters: letters, numbers, dot, dash or underscore.')}>Continue</button>
          </Card>
        )}
        {step === 'existing' && (
          <Card title="That username already exists" sub={`If ${username} is your account, enter your password.`}>
            <Field label="Password"><input type="password" autocomplete="current-password" value={password} onInput={e => setPassword((e.target as HTMLInputElement).value)} /></Field>
            <button class="filled wide" disabled={busy || !password} onClick={() => run(async () => signedIn(await login(username, password)))}>Sign in</button>
            <button onClick={() => { setStep('reset'); setPassword('') }}>Forgot your password?</button>
            <button onClick={() => { setStep('name'); setPassword('') }}>Use a different username</button>
          </Card>
        )}
        {step === 'new' && (
          <Card title={`${username} is available`} sub="Choose a password to create your account.">
            <Field label="Password"><input type="password" autocomplete="new-password" value={password} onInput={e => setPassword((e.target as HTMLInputElement).value)} /></Field>
            <Field label="Type the password again"><input type="password" autocomplete="new-password" value={confirm} onInput={e => setConfirm((e.target as HTMLInputElement).value)} /></Field>
            <div class="sub">At least 8 characters. Next you'll get a recovery code to keep, in case you forget it.</div>
            <button class="filled wide" disabled={busy} onClick={() => newPasswordProblem()
              ? setError(newPasswordProblem()) : run(async () => signedIn(await register(username, password)))}>Create account</button>
            <button onClick={() => { setStep('name'); setPassword(''); setConfirm('') }}>Use a different username</button>
          </Card>
        )}
        {step === 'reset' && (
          <Card title="Forgot your password?" sub={`Enter the recovery code you saved when you made your account (or a reset code from the owner or an admin), and choose a new password for ${username}.`}>
            <Field label="Recovery or reset code"><input autocapitalize="characters" value={code} onInput={e => setCode((e.target as HTMLInputElement).value)} /></Field>
            <Field label="New password"><input type="password" autocomplete="new-password" value={password} onInput={e => setPassword((e.target as HTMLInputElement).value)} /></Field>
            <Field label="Type the new password again"><input type="password" autocomplete="new-password" value={confirm} onInput={e => setConfirm((e.target as HTMLInputElement).value)} /></Field>
            <div class="sub">Your other phones are signed out, and you get a new recovery code (the old one stops working). No code? Ask the owner or an admin for a reset code: they'll check it's you.</div>
            <button class="filled wide" disabled={busy || !code} onClick={() => newPasswordProblem()
              ? setError(newPasswordProblem()) : run(async () => signedIn(await resetPassword(username, code, password)))}>Set new password</button>
          </Card>
        )}
        {error && <div class="card err">{error}</div>}
      </main>
      <RecoveryDialog code={recovery} onDone={() => { setRecovery(null); done() }} />
      <Dialog open={!!pending} title="Rounds on this device" onClose={() => answer(null)}
        actions={<><button onClick={() => answer(null)}>Cancel</button>
          <button class="danger" onClick={() => answer(false)}>Remove {them}</button>
          <button class="filled" onClick={() => answer(true)}>Add to {username}</button></>}>
        {plan && signInQuestion(plan, username)}
      </Dialog>
    </>
  )
}

/** Profile: the account, and your class / age group / bow (copied onto each new round). */
export function ProfileScreen() {
  const account = useData(getAccount)
  const [p, setP] = useState<Profile | null>(null)
  useEffect(() => { getProfile().then(setP) }, [])
  const [ask, setAsk] = useState<'recovery' | 'delete' | null>(null)
  const [password, setPassword] = useState('')
  const [recovery, setRecovery] = useState<string | null>(null)
  const [signingOut, setSigningOut] = useState(false)
  const [loss, setLoss] = useState<string | null>(null)
  const [removeToo, setRemoveToo] = useState(false)
  const [deleted, setDeleted] = useState(false)
  if (!p) return null
  const update = (change: Partial<Profile>) => setP({ ...p, ...change })
  const fromDob = p.dateOfBirth ? ageGroupFor(p.dateOfBirth, today()) : null

  async function confirmAsk() {
    const what = ask, pw = password
    setAsk(null); setPassword('')
    try {
      if (what === 'recovery') setRecovery(await newRecoveryCode(pw))
      else { await deleteAccount(pw); setDeleted(true) }
    } catch (e) { toast(errorText(e)) }
  }

  return (
    <>
      <Bar title="Profile"><button onClick={async () => { await saveProfile(p); back() }}>Save</button></Bar>
      <main class="form">
        <Card title="Account" sub="Optional. Scoring works without it.">
          {account ? <>
            <div class="row between"><div><div class="sub">Signed in as</div><b style="font-size:20px">{account.username}</b></div>
              <button class="outlined" onClick={async () => { setLoss(signOutWarning(await signOutCheck())); setRemoveToo(false); setSigningOut(true) }}>Sign out</button></div>
            <div class="sub">Lost your recovery code, or made your account before there was one? Make a new one (the old one stops working).</div>
            <button class="outlined" onClick={() => setAsk('recovery')}>New recovery code</button>
            <button class="danger" onClick={() => setAsk('delete')}>Delete account</button>
          </> : <>
            <div>Sign in to back up your rounds to the server and share scorecards with friends.</div>
            <button class="filled wide" onClick={() => go('/account')}>Sign in or create account</button>
          </>}
        </Card>
        <Card title="About you" sub="Stored on this phone only. Never sent anywhere.">
          <Field label="Your name"><input value={p.realName ?? ''} onInput={e => update({ realName: (e.target as HTMLInputElement).value })} /></Field>
          <Field label="Date of birth (optional)" help="Used to work out your age group on each shoot day">
            <input type="date" value={p.dateOfBirth ?? ''} onInput={e => update({ dateOfBirth: (e.target as HTMLInputElement).value || undefined })} />
          </Field>
        </Card>
        <Card title="Archery" sub="Copied onto each round, and shared with your scores when signed in.">
          <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={p.archerClass} label={c => CLASS_NAMES[c]} onChange={c => update({ archerClass: c })} />
          <Select<AgeGroup> label="Age group" value={p.ageGroupManual} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]}
            none={fromDob ? `Automatic (${AGE_NAMES[fromDob]} today)` : 'Not set'} onChange={a => update({ ageGroupManual: a })} />
          <div class="sub">Leave on automatic to work it out from your date of birth</div>
          <Select label="Main bow style" value={p.bowStyle} options={BOW_STYLES.map(b => b.code)} none="Not set"
            optionLabel={c => `${BOW_STYLES.find(b => b.code === c)!.name} (${c})`} onChange={b => update({ bowStyle: b })} />
        </Card>
      </main>
      <Dialog open={signingOut} title="Sign out?" onClose={() => setSigningOut(false)}
        actions={<><button onClick={() => setSigningOut(false)}>Cancel</button>
          <button class={removeToo && loss ? 'danger' : ''} onClick={async () => { setSigningOut(false); await signOut(removeToo); toast('Signed out.'); go('/') }}>
            {removeToo && loss ? 'Sign out anyway' : 'Sign out'}</button></>}>
        <p>Your rounds, profile and Stats choices are put away on this device: nobody else signing in here sees them, and
          they're all back when you sign in again.</p>
        <label class="row" style="gap:8px"><input type="checkbox" style="width:auto" checked={removeToo}
          onChange={e => setRemoveToo((e.target as HTMLInputElement).checked)} /> Also remove my rounds from this device</label>
        {removeToo && <p class="sub">They come back from the server when you sign in again; your real name and date of birth don't (they're never sent anywhere).</p>}
        {removeToo && loss && <div class="card err">{loss}</div>}
      </Dialog>
      <Dialog open={!!ask} title={ask === 'delete' ? 'Delete your account?' : 'New recovery code'} onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Cancel</button>
          <button class={ask === 'delete' ? 'danger' : ''} disabled={!password} onClick={confirmAsk}>{ask === 'delete' ? 'Delete account' : 'Make code'}</button></>}>
        <p>{ask === 'delete'
          ? 'This deletes your account and everything the server keeps about it: your rounds and scores there, friends, round requests and your place in live groups. It can\'t be undone. Rounds on this phone stay here.'
          : 'Enter your password to make a new recovery code.'}</p>
        <input type="password" autocomplete="current-password" placeholder="Password" value={password} onInput={e => setPassword((e.target as HTMLInputElement).value)} />
      </Dialog>
      <RecoveryDialog code={recovery} onDone={() => setRecovery(null)} />
      <Dialog open={deleted} title="Account deleted" onClose={() => setDeleted(false)} actions={<button onClick={() => setDeleted(false)}>OK</button>}>
        Your account and everything the server kept about it have been deleted. Your rounds are still on this phone.
      </Dialog>
    </>
  )
}
