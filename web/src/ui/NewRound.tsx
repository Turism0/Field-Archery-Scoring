import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Select, Seg, errorText, go, replace, today, useData } from './common'
import { cachedFriends, getAccount, getProfile, type Friend } from '../data/account'
import { startRound, deleteRound, type MemberDraft } from '../data/rounds'
import { create, METHODS, type LiveMethod } from '../data/live'
import { getRound } from '../data/db'
import { captureFor } from '../data/locations'
import {
  AGE_NAMES, BOW_STYLES, CLASS_NAMES, MAX_GROUP, MAX_START_TARGET, MAX_TARGETS, ROUND_TYPE_NAMES, ageGroupFor, startTargetOk, type AgeGroup, type ArcherClass,
} from '../domain/round'
import type { RoundType } from '../domain/scoring'

type Mode = 'solo' | 'group' | 'live'
const COMMON_COUNTS = [18, 20, 36, 40]
const bowLabel = (c: string) => `${BOW_STYLES.find(b => b.code === c)?.name ?? c} (${c})`

/** A new round (as the app's, after choosing the round type): who's scoring, the shoot, you, and the group. */
export function NewRound({ type }: { type: RoundType }) {
  const account = useData(getAccount)
  const friends = useData(cachedFriends) ?? []
  const [mode, setMode] = useState<Mode>('solo')
  const [date, setDate] = useState(today())
  const [targets, setTargets] = useState('')
  const [startText, setStartText] = useState('')
  const [cls, setCls] = useState<ArcherClass>()
  const [ageChoice, setAgeChoice] = useState<AgeGroup>()
  const [bow, setBow] = useState<string>()
  const [profile, setProfile] = useState<{ dob?: string; manual?: AgeGroup; bow?: string }>({})
  const [members, setMembers] = useState<(MemberDraft & { key: number })[]>([])
  const [dialog, setDialog] = useState<{ role: 'FRIEND' | 'GUEST'; existing?: MemberDraft & { key: number } } | null>(null)
  const [method, setMethod] = useState<LiveMethod>('SINGLE')
  const [expected, setExpected] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getProfile().then(p => {
      setCls(c => c ?? p.archerClass); setBow(b => b ?? p.bowStyle)
      setProfile({ dob: p.dateOfBirth, manual: p.ageGroupManual, bow: p.bowStyle })
    })
  }, [])

  // Age group: chosen for this round, else the profile's (set by hand, or from the date of birth on the shoot date).
  const fromProfile = profile.manual ?? (profile.dob ? ageGroupFor(profile.dob, date) ?? undefined : undefined)
  const age = ageChoice ?? fromProfile
  const ageSource = ageChoice ? 'Chosen for this round' : profile.manual ? 'From profile' : profile.dob ? 'From date of birth' : 'Not set'
  const group = mode !== 'solo', isLive = mode === 'live'

  const count = Number(targets)
  const countOk = Number.isInteger(count) && count >= 1 && count <= MAX_TARGETS
  const startN = Number(startText)
  const startOk = !startText || startTargetOk(startN)
  const exp = Number(expected)
  const expOk = !isLive || (Number.isInteger(exp) && exp >= members.length + 1 && exp <= MAX_GROUP)
  const valid = countOk && startOk && !!cls && !!age && !!bow && (mode !== 'group' || members.length > 0) && expOk && (!isLive || !!account)
  const available = friends.filter(f => !members.some(m => m.archerId === f.archerId))
  const canAdd = members.length + 1 < MAX_GROUP

  async function start() {
    if (!valid || busy) return
    setBusy(true); setError(null)
    try {
      const id = await startRound({ shootDate: date, targetCount: count, archerClass: cls!, ageGroup: age!, bowStyle: bow!, type,
        members: group ? members.map(({ key: _, ...m }) => m) : [], groupId: isLive ? crypto.randomUUID() : undefined,
        startTarget: startText ? startN : null })
      captureFor(id)   // shoot location: one GPS position, looked up when there's signal
      if (isLive) {
        const r = (await getRound(id))!
        try { await create(r.groupId!, method, exp) } catch (e) { await deleteRound(r); throw e }
        replace(`/lobby/${r.groupId}`)
      } else replace(`/score/${id}`)
    } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }

  const move = (i: number, by: number) => setMembers(ms => {
    const j = i + by
    if (j < 0 || j >= ms.length) return ms
    const c = ms.slice(); [c[i], c[j]] = [c[j], c[i]]; return c
  })

  return (
    <>
      <Bar title="New round" sub={ROUND_TYPE_NAMES[type]}>
        <button class="icon" aria-label="Edit profile" onClick={() => go('/profile')}>✎</button>
      </Bar>
      <main class="form">
        <Card title="Who's scoring">
          <Seg<Mode> options={['solo', 'group', 'live']} value={mode} label={m => ({ solo: 'Just me', group: 'Group', live: 'Live group' })[m]} onChange={m => { setMode(m); setError(null) }} />
          {mode === 'group' && <div class="sub">You score everyone on this phone, archer by archer on each target.</div>}
          {isLive && <>
            <div class="sub">Others join on their own phones with a code or QR. Needs signing in, and signal on the web app (the Android app also shares scores phone to phone with no signal).</div>
            {!account && <div class="error">Sign in (Profile) to make a live group.</div>}
            {METHODS.map(m => (
              <label class="row" style="align-items:flex-start;flex-wrap:nowrap">
                <input type="radio" name="method" style="width:auto;margin-top:4px" checked={method === m.code} onChange={() => setMethod(m.code)} />
                <span><span>{m.name}</span><div class="sub">{m.help}</div></span>
              </label>
            ))}
            <Field label="Archers in the group (with you)" help={expected && !expOk ? `${members.length + 1} to ${MAX_GROUP}` : 'Start waits until this many are in (friends and guests you add count).'}>
              <input inputMode="numeric" value={expected} onInput={e => setExpected((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 2))} />
            </Field>
            {METHODS.find(m => m.code === method)?.checked && <div class="sub">Go through the group archer by archer: they call their score and both their scorers enter it. Everyone moves on once the two match.</div>}
          </>}
        </Card>
        <Card title="Shoot">
          <Field label="Shoot date"><input type="date" value={date} onInput={e => setDate((e.target as HTMLInputElement).value)} /></Field>
          <Field label="Number of targets" help={targets && !countOk ? `1 to ${MAX_TARGETS}` : undefined}>
            <input inputMode="numeric" value={targets} onInput={e => setTargets((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} /></Field>
          <div class="row">{COMMON_COUNTS.map(n => <button class={`chip ${count === n ? 'on' : ''}`} onClick={() => setTargets(String(n))}>{n}</button>)}</div>
          <Field label="Starting target (optional)" help={!startOk ? `1 to ${MAX_START_TARGET}` : 'The course\'s number on the first target you shoot, if not 1 (e.g. 19 for the second half of a 36-target course). Just noted on the scorecard: scoring starts at 1.'}>
            <input inputMode="numeric" value={startText} onInput={e => setStartText((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} />
          </Field>
        </Card>
        <Card title={group ? 'You' : 'Archer'} sub="Filled in from your profile. Changes here only affect this round.">
          <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={setCls} />
          <Field label="Age group" help={ageSource}>
            <select value={age ?? ''} onChange={e => setAgeChoice(((e.target as HTMLSelectElement).value || undefined) as AgeGroup | undefined)}>
              {!age && <option value="">Choose…</option>}
              {(['ADULT', 'JUNIOR', 'CUB'] as AgeGroup[]).map(a => <option value={a}>{AGE_NAMES[a]}</option>)}
            </select>
          </Field>
          <Field label="Bow style" help={bow && bow === profile.bow ? 'Your main style' : undefined}>
            <select value={bow ?? ''} onChange={e => setBow((e.target as HTMLSelectElement).value || undefined)}>
              {!bow && <option value="">Choose…</option>}
              {BOW_STYLES.map(b => <option value={b.code}>{bowLabel(b.code)}</option>)}
            </select>
          </Field>
        </Card>
        {group && (
          <Card title="Group" sub={isLive ? 'Friends and guests you add are scored on your phone (optional: others can join with the code).'
            : `In scoring order. You're first; up to ${MAX_GROUP} archers.`}>
            {members.map((m, i) => (
              <div class="row" style="flex-wrap:nowrap;gap:0">
                <span class="grow"><b>{m.name}</b><div class="sub">{m.role === 'FRIEND' ? 'Friend' : 'Guest'} · {CLASS_NAMES[m.archerClass]} {AGE_NAMES[m.ageGroup]} {m.bowStyle}</div></span>
                <button class="icon" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>▲</button>
                <button class="icon" aria-label="Move down" disabled={i === members.length - 1} onClick={() => move(i, 1)}>▼</button>
                <button class="icon" aria-label={`Edit ${m.name}`} onClick={() => setDialog({ role: m.role, existing: m })}>✎</button>
                <button class="icon" aria-label={`Remove ${m.name}`} onClick={() => setMembers(ms => ms.filter(x => x.key !== m.key))}>✕</button>
              </div>
            ))}
            <div class="row">
              <button class="outlined" disabled={!canAdd || !available.length} onClick={() => setDialog({ role: 'FRIEND' })}>Add friend</button>
              <button class="outlined" disabled={!canAdd} onClick={() => setDialog({ role: 'GUEST' })}>Add guest</button>
            </div>
            {!available.length && canAdd && <div class="sub">{!account ? 'Sign in (Profile) to add friends; guests can be added any time.'
              : 'No friends to add. Open Friends once with signal to load your list.'}</div>}
          </Card>
        )}
        {!valid && <div class="sub">{mode === 'group' && !members.length ? 'Add at least one friend or guest to the group.'
          : 'Fill in the number of targets, class, age group and bow style to start.'}</div>}
        {error && <div class="card err">{error}</div>}
        <button class="filled wide" disabled={!valid || busy} onClick={start}>
          {isLive ? (busy ? 'Creating…' : 'Create live group') : group ? 'Start group round' : 'Start round'}</button>
      </main>
      {dialog && <MemberDialog role={dialog.role} existing={dialog.existing}
        friends={friends.filter(f => !members.some(m => m.archerId === f.archerId && m.key !== dialog.existing?.key))}
        onClose={() => setDialog(null)}
        onSave={m => {
          setMembers(ms => dialog.existing ? ms.map(x => x.key === dialog.existing!.key ? { ...m, key: x.key } : x) : [...ms, { ...m, key: Date.now() }])
          setDialog(null)
        }} />}
    </>
  )
}

/** Add or edit a friend (picked from your friends list) or a guest (a name), each with their own class, age group and bow. */
function MemberDialog({ role, existing, friends, onClose, onSave }: {
  role: 'FRIEND' | 'GUEST'; existing?: MemberDraft; friends: Friend[]; onClose: () => void; onSave: (m: MemberDraft) => void
}) {
  const [friendId, setFriendId] = useState(existing?.archerId)
  const [name, setName] = useState(existing?.name ?? '')
  const [cls, setCls] = useState<ArcherClass | undefined>(existing?.archerClass)
  const [age, setAge] = useState<AgeGroup>(existing?.ageGroup ?? 'ADULT')
  const [bow, setBow] = useState<string | undefined>(existing?.bowStyle)
  const isFriend = role === 'FRIEND'
  const friend = friends.find(f => f.archerId === friendId)
  const who = isFriend ? friend?.username ?? existing?.name : name.trim() || undefined
  const ok = !!who && !!cls && !!bow
  return (
    <Dialog open title={existing ? `Edit ${existing.name}` : isFriend ? 'Add a friend' : 'Add a guest'} onClose={onClose}
      actions={<><button onClick={onClose}>Cancel</button><button disabled={!ok} onClick={() => onSave({
        role, name: who!, archerId: isFriend ? friend?.archerId ?? existing?.archerId : undefined, archerClass: cls!, ageGroup: age, bowStyle: bow!,
      })}>{existing ? 'Save' : 'Add'}</button></>}>
      <div class="col">
        {isFriend ? (existing ? <b>{existing.name}</b>
          : <Select label="Friend" value={friendId} options={friends.map(f => f.archerId)} optionLabel={id => friends.find(f => f.archerId === id)!.username} onChange={setFriendId} />)
          : <Field label="Guest's name" help="Kept on this phone only, for 30 days."><input maxLength={40} value={name} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>}
        {isFriend && !existing && <div class="sub">Their round is sent to them to accept when you finish.</div>}
        <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={setCls} />
        <Select<AgeGroup> label="Age group" value={age} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]} onChange={a => a && setAge(a)} />
        <Select label="Bow style" value={bow} options={BOW_STYLES.map(b => b.code)} optionLabel={bowLabel} onChange={setBow} />
      </div>
    </Dialog>
  )
}
