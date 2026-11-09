import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Field, Select, Seg, errorText, replace, today, useData } from './common'
import { cachedFriends, getAccount, getProfile } from '../data/account'
import { startRound, deleteRound, type MemberDraft } from '../data/rounds'
import { create, METHODS, type LiveMethod } from '../data/live'
import { getRound } from '../data/db'
import { captureFor } from '../data/locations'
import {
  AGE_NAMES, BOW_STYLES, CLASS_NAMES, MAX_GROUP, MAX_TARGETS, ROUND_TYPE_HELP, ROUND_TYPE_NAMES, ageGroupFor,
  type AgeGroup, type ArcherClass,
} from '../domain/round'
import type { RoundType } from '../domain/scoring'

type Mode = 'solo' | 'group' | 'live'

/** A new round: type, who's scoring, the shoot, and you (and the others in a group). */
export function NewRound() {
  const account = useData(getAccount)
  const friends = useData(cachedFriends) ?? []
  const [type, setType] = useState<RoundType>('BIG_GAME')
  const [mode, setMode] = useState<Mode>('solo')
  const [date, setDate] = useState(today())
  const [targets, setTargets] = useState('')
  const [startText, setStartText] = useState('')
  const [cls, setCls] = useState<ArcherClass>()
  const [age, setAge] = useState<AgeGroup>()
  const [bow, setBow] = useState<string>()
  const [dob, setDob] = useState<string>()
  const [members, setMembers] = useState<MemberDraft[]>([])
  const [method, setMethod] = useState<LiveMethod>('SINGLE')
  const [expected, setExpected] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getProfile().then(p => {
      setCls(c => c ?? p.archerClass); setBow(b => b ?? p.bowStyle); setDob(p.dateOfBirth)
      setAge(a => a ?? p.ageGroupManual ?? (p.dateOfBirth ? ageGroupFor(p.dateOfBirth, today()) ?? undefined : undefined))
    })
  }, [])
  useEffect(() => { if (dob) setAge(a => a ?? ageGroupFor(dob, date) ?? undefined) }, [date, dob])

  const count = Number(targets)
  const countOk = Number.isInteger(count) && count >= 1 && count <= MAX_TARGETS
  const startN = Number(startText)
  const startOk = !startText || (countOk && startN >= 1 && startN <= count)
  const exp = Number(expected)
  const expOk = mode !== 'live' || (Number.isInteger(exp) && exp >= members.length + 1 && exp <= MAX_GROUP)
  const valid = countOk && startOk && cls && age && bow && (mode !== 'group' || members.length > 0) && expOk && (mode !== 'live' || !!account)

  async function start() {
    if (!valid || busy) return
    setBusy(true); setError(null)
    try {
      const id = await startRound({ shootDate: date, targetCount: count, archerClass: cls!, ageGroup: age!, bowStyle: bow!, type,
        members: mode === 'solo' ? [] : members, groupId: mode === 'live' ? crypto.randomUUID() : undefined,
        startTarget: startText ? startN : null })
      captureFor(id)   // shoot location: one GPS position, looked up when there's signal
      if (mode === 'live') {
        const r = (await getRound(id))!
        try { await create(r.groupId!, method, exp) } catch (e) { await deleteRound(r); throw e }
        replace(`/lobby/${r.groupId}`)
      } else replace(`/score/${id}`)
    } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }

  const updateMember = (i: number, change: Partial<MemberDraft>) => setMembers(ms => ms.map((m, j) => j === i ? { ...m, ...change } : m))
  const move = (i: number, by: number) => setMembers(ms => {
    const j = i + by
    if (j < 0 || j >= ms.length) return ms
    const c = ms.slice(); [c[i], c[j]] = [c[j], c[i]]; return c
  })

  return (
    <>
      <Bar title="New round" />
      <main class="form">
        <Card title="Round type">
          <Seg<RoundType> options={['BIG_GAME', 'WOODSMAN']} value={type} label={t => ROUND_TYPE_NAMES[t]} onChange={setType} />
          <div class="sub">{ROUND_TYPE_HELP[type]}</div>
        </Card>
        <Card title="Who's scoring">
          <Seg<Mode> options={['solo', 'group', 'live']} value={mode} label={m => ({ solo: 'Just me', group: 'Group', live: 'Live group' })[m]} onChange={setMode} />
          {mode === 'group' && <div class="sub">You score everyone on this phone, archer by archer on each target.</div>}
          {mode === 'live' && <>
            <div class="sub">Others join on their own phones with a code. Needs signal on the web (the Android app also works phone to phone).</div>
            {!account && <div class="error">Sign in (Profile) to make a live group.</div>}
            {METHODS.map(m => (
              <label class="row" style="align-items:flex-start;flex-wrap:nowrap">
                <input type="radio" name="method" style="width:auto;margin-top:4px" checked={method === m.code} onChange={() => setMethod(m.code)} />
                <span><b>{m.name}</b><div class="sub">{m.help}</div></span>
              </label>
            ))}
            <Field label="Archers in the group (with you)" help="Start waits until this many are in (friends and guests you add count).">
              <input inputMode="numeric" value={expected} onInput={e => setExpected((e.target as HTMLInputElement).value.replace(/\D/g, ''))} />
            </Field>
          </>}
        </Card>
        <Card title="Shoot">
          <Field label="Shoot date"><input type="date" value={date} onInput={e => setDate((e.target as HTMLInputElement).value)} /></Field>
          <Field label="Number of targets"><input inputMode="numeric" value={targets} onInput={e => setTargets((e.target as HTMLInputElement).value.replace(/\D/g, ''))} /></Field>
          <div class="row">{[20, 24, 30, 36, 40].map(n => <button class={`chip ${count === n ? 'on' : ''}`} onClick={() => setTargets(String(n))}>{n}</button>)}</div>
          <Field label="Starting target (optional)" help={!startOk ? `1 to ${countOk ? count : '–'}` : 'The first target you shoot, if not 1. Just noted on the scorecard.'}>
            <input inputMode="numeric" value={startText} onInput={e => setStartText((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} />
          </Field>
        </Card>
        <Card title={mode === 'solo' ? 'Archer' : 'You'} sub="From your profile. Changes here only affect this round.">
          <ArcherFields cls={cls} age={age} bow={bow} onChange={c => { if (c.cls) setCls(c.cls); if (c.age) setAge(c.age); if (c.bow) setBow(c.bow) }} />
        </Card>
        {mode !== 'solo' && (
          <Card title={`In the group (${members.length + 1})`} sub={mode === 'live' ? 'Friends and guests without a phone. Archers with phones join with the code.' : 'In scoring order, after you.'}>
            {members.map((m, i) => (
              <div class="card low">
                <div class="row between"><b>{m.name}{m.role === 'FRIEND' ? ' (friend)' : ''}</b>
                  <span><button class="icon" aria-label="Up" onClick={() => move(i, -1)}>▲</button><button class="icon" aria-label="Down" onClick={() => move(i, 1)}>▼</button>
                    <button class="icon" aria-label="Remove" onClick={() => setMembers(ms => ms.filter((_, j) => j !== i))}>✕</button></span></div>
                <ArcherFields cls={m.archerClass} age={m.ageGroup} bow={m.bowStyle}
                  onChange={c => updateMember(i, { ...(c.cls ? { archerClass: c.cls } : {}), ...(c.age ? { ageGroup: c.age } : {}), ...(c.bow ? { bowStyle: c.bow } : {}) })} />
              </div>
            ))}
            {members.length + 1 < MAX_GROUP && <AddMember friends={friends.filter(f => !members.some(m => m.archerId === f.archerId))}
              onAdd={m => setMembers(ms => [...ms, m])} defaults={{ archerClass: cls ?? 'GENTS', ageGroup: age ?? 'ADULT', bowStyle: bow ?? 'LB' }} />}
          </Card>
        )}
        {error && <div class="card err">{error}</div>}
        <button class="filled wide" disabled={!valid || busy} onClick={start}>{busy ? 'Starting…' : mode === 'live' ? 'Make the live group' : 'Start scoring'}</button>
      </main>
    </>
  )
}

function ArcherFields({ cls, age, bow, onChange }: {
  cls?: ArcherClass; age?: AgeGroup; bow?: string; onChange: (c: { cls?: ArcherClass; age?: AgeGroup; bow?: string }) => void
}) {
  return <>
    <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={c => onChange({ cls: c })} />
    <Select<AgeGroup> label="Age group" value={age} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]} onChange={a => onChange({ age: a })} />
    <Select label="Bow style" value={bow} options={BOW_STYLES.map(b => b.code)} optionLabel={c => `${BOW_STYLES.find(b => b.code === c)!.name} (${c})`}
      onChange={b => onChange({ bow: b })} />
  </>
}

function AddMember({ friends, onAdd, defaults }: {
  friends: { archerId: string; username: string }[]; onAdd: (m: MemberDraft) => void
  defaults: { archerClass: ArcherClass; ageGroup: AgeGroup; bowStyle: string }
}) {
  const [name, setName] = useState('')
  return (
    <div class="col" style="display:flex;flex-direction:column;gap:8px">
      {friends.length > 0 && (
        <Select label="Add a friend" value={undefined} options={friends.map(f => f.archerId)} none="Choose a friend…"
          optionLabel={id => friends.find(f => f.archerId === id)!.username}
          onChange={id => { const f = friends.find(x => x.archerId === id); if (f) onAdd({ role: 'FRIEND', name: f.username, archerId: f.archerId, ...defaults }) }} />
      )}
      <div class="row" style="flex-wrap:nowrap">
        <input placeholder="Guest's name" maxLength={40} value={name} onInput={e => setName((e.target as HTMLInputElement).value)} />
        <button class="tonal" disabled={!name.trim()} onClick={() => { onAdd({ role: 'GUEST', name: name.trim(), ...defaults }); setName('') }}>Add</button>
      </div>
    </div>
  )
}
