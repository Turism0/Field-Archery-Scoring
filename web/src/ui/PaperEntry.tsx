import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Select, Seg, back, errorText, replace, toast, today, useData } from './common'
import { getAccount, getProfile } from '../data/account'
import { getRound } from '../data/db'
import { saveTypedRound } from '../data/rounds'
import { NameTaken, createNamed, knownLabel, knownLocations, type MapLocation } from '../data/locations'
import {
  AGE_NAMES, BOW_STYLES, CLASS_NAMES, MAX_START_TARGET, MAX_TARGETS, ROUND_TYPE_HELP, ROUND_TYPE_NAMES, ageGroupFor, startTargetOk, type AgeGroup, type ArcherClass, type Round,
} from '../domain/round'
import { MAX_FAN, MIN_FAN, isComplete, totalPoints, type RoundType, type TargetScore, type TargetType } from '../domain/scoring'
import { cellDisabled, emptyRow, rowToTarget, targetToRow, withRowType, type RowEntry } from '../domain/paper'

const KINDS: { value: string; label: string }[] = [
  { value: 'STANDARD', label: 'Standard' }, { value: 'PREDATOR_PREY', label: 'Predator / prey' },
  ...Array.from({ length: MAX_FAN - MIN_FAN + 1 }, (_, i) => ({ value: `FAN${i + MIN_FAN}`, label: `Fan of ${i + MIN_FAN}` })),
  { value: 'FUN', label: 'Fun shot (any score)' },
]
const kindOf = (r: RowEntry) => (r.type === 'FAN' ? `FAN${r.cells.length}` : r.type)

/** The location choice for typing a new name-only location (3.0). */
const NEW_NAME = '__new_name_only'

/**
 * Desktop (2.6.02): type in a round shot and scored on paper, or change a round's scores. One box per animal
 * with its points; the arrows and hits are worked out from them.
 */
export function PaperEntry({ id }: { id?: string }) {
  const account = useData(getAccount)
  const [existing, setExisting] = useState<Round | null>(null)
  const [type, setType] = useState<RoundType>('BIG_GAME')
  const [date, setDate] = useState(today())
  const [count, setCount] = useState('')
  const [cls, setCls] = useState<ArcherClass>()
  const [age, setAge] = useState<AgeGroup>()
  const [bow, setBow] = useState<string>()
  const [locId, setLocId] = useState<string>('')
  const [start, setStart] = useState('')
  // 3.0: a new name-only location (no map pin) typed here, made when the round is saved.
  const [newName, setNewName] = useState('')
  const [known, setKnown] = useState<MapLocation[]>([])
  const [rows, setRows] = useState<RowEntry[]>([])
  const [busy, setBusy] = useState(false)
  const [ask, setAsk] = useState<'unfinished' | null>(null)

  useEffect(() => {
    if (id) getRound(id).then(r => {
      if (!r) return
      setExisting(r); setType(r.type); setDate(r.shootDate); setCount(String(r.targetCount)); setCls(r.archerClass); setAge(r.ageGroup)
      setBow(r.bowStyle); setLocId(r.locationId ?? ''); setStart(r.startTarget ? String(r.startTarget) : '')
      setRows(Array.from({ length: r.targetCount }, (_, i) => { const t = r.targets.find(x => x.targetNo === i + 1); return t ? targetToRow(t) : emptyRow(r.type) }))
      if (r.locationId && r.locationName) setKnown(k => k.some(x => x.id === r.locationId) ? k : [...k, { id: r.locationId!, name: r.locationName!, latitude: null, longitude: null, isPrivate: false, myRounds: 0, rounds: 0 }])
    })
    else getProfile().then(p => {
      setCls(p.archerClass); setBow(p.bowStyle)
      setAge(p.ageGroupManual ?? (p.dateOfBirth ? ageGroupFor(p.dateOfBirth, today()) ?? undefined : undefined))
    })
  }, [id])
  useEffect(() => { if (account) knownLocations().then(l => setKnown(k => [...l, ...k.filter(x => !l.some(y => y.id === x.id))]), () => {}) }, [account?.token])

  const n = Number(count)
  const countOk = Number.isInteger(n) && n >= 1 && n <= MAX_TARGETS
  // Rows follow the number of targets (and the round type for a new round).
  useEffect(() => {
    if (!countOk) return
    setRows(rs => Array.from({ length: n }, (_, i) => (rs[i] && (type === 'WOODSMAN') === (rs[i].type === 'WOODSMAN') ? rs[i] : emptyRow(type))))
  }, [n, type])

  const results = rows.map((r, i) => rowToTarget(type, i + 1, r))
  const errors = results.filter(x => 'error' in x).length
  const targets = results.map(x => ('target' in x ? x.target : null))
  const scored = targets.filter(t => t && isComplete(t)).length
  const total = targets.reduce((s, t) => s + (t ? totalPoints(t) : 0), 0)
  const startOk = !start || startTargetOk(Number(start))
  const newOk = locId !== NEW_NAME || newName.trim().length >= 2
  const valid = countOk && startOk && newOk && cls && age && bow && date && !errors && rows.length === n

  const setCell = (i: number, j: number, v: string) => setRows(rs => rs.map((r, k) => k === i ? { ...r, cells: r.cells.map((c, m) => m === j ? v : c) } : r))
  const setKind = (i: number, v: string) => setRows(rs => rs.map((r, k) => {
    if (k !== i) return r
    return v.startsWith('FAN') ? withRowType(r, 'FAN', Number(v.slice(3))) : withRowType(r, v as TargetType)
  }))

  /** Enter goes to the next box, like Tab. */
  function onKey(e: KeyboardEvent) {
    if (e.key !== 'Enter') return
    e.preventDefault()
    const all = [...document.querySelectorAll<HTMLInputElement>('input.cell:not(:disabled)')]
    const i = all.indexOf(e.target as HTMLInputElement)
    all[i + 1]?.focus(); all[i + 1]?.select()
  }

  async function save(finished: boolean) {
    setAsk(null); setBusy(true)
    try {
      let loc: { id: string; name: string } | undefined = known.find(k => k.id === locId)
      if (locId === NEW_NAME) {
        try { loc = await createNamed(newName, false) } catch (e) {
          if (!(e instanceof NameTaken)) throw e
          loc = e.existing; toast(`Used the existing location "${e.existing.name}".`)
        }
      }
      const rid = await saveTypedRound({
        id: existing?.id, type, shootDate: date, targetCount: n, archerClass: cls!, ageGroup: age!, bowStyle: bow!,
        targets: targets as TargetScore[],
        finished, startTarget: start ? Number(start) : null, location: loc ? { id: loc.id, name: loc.name } : locId === '' ? null : undefined,
      })
      toast(existing ? 'Saved.' : finished ? 'Round added.' : 'Saved as in progress.')
      existing ? back() : replace(`/card/${rid}`)
    } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }

  if (id && !existing) return <Bar title="Edit scores" />
  const half = Math.ceil(rows.length / 2)
  return (
    <>
      <Bar title={existing ? 'Edit scores' : 'Add a round'} />
      <main>
        <div class="split"><div>
          <Card title="Round" sub={existing ? undefined : 'A round shot and scored on paper (or scored before): fill in the details, then each target\'s points.'}>
            {!existing && <><Seg<RoundType> options={['BIG_GAME', 'WOODSMAN']} value={type} label={t => ROUND_TYPE_NAMES[t]} onChange={setType} />
              <div class="sub">{ROUND_TYPE_HELP[type]}</div></>}
            <div class="row" style="flex-wrap:nowrap">
              <Field label="Shoot date"><input type="date" value={date} max={today()} disabled={!!existing?.groupId} onInput={e => setDate((e.target as HTMLInputElement).value)} /></Field>
              <Field label="Targets"><input inputMode="numeric" value={count} disabled={!!existing?.groupId} onInput={e => setCount((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} /></Field>
            </div>
            <Field label="Starting target (optional)" help={!startOk ? `1 to ${MAX_START_TARGET}` : 'The course\'s number on the first target shot, if not 1 (any target on the course). The sheet still starts at 1.'}>
              <input inputMode="numeric" value={start} style="max-width:120px" onInput={e => setStart((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} />
            </Field>
            {existing?.groupId && <div class="sub">A group round: change its date or number of targets with Edit round details (it changes everyone's).</div>}
            {!existing?.groupId && <div class="row">{[20, 24, 30, 36, 40].map(t => <button class={`chip ${n === t ? 'on' : ''}`} onClick={() => setCount(String(t))}>{t}</button>)}</div>}
            <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={setCls} />
            <div class="row" style="flex-wrap:nowrap">
              <Select<AgeGroup> label="Age group" value={age} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]} onChange={setAge} />
              <Select label="Bow style" value={bow} options={BOW_STYLES.map(b => b.code)} optionLabel={c => `${BOW_STYLES.find(b => b.code === c)!.name} (${c})`} onChange={setBow} />
            </div>
            <label class="field">Shoot location
              <select value={locId} onChange={e => setLocId((e.target as HTMLSelectElement).value)} disabled={!account}>
                <option value="">{account ? 'No location' : 'Sign in to choose a location'}</option>
                {known.map(k => <option value={k.id}>{knownLabel(k)}</option>)}
                {account && <option value={NEW_NAME}>New name only – no map pin…</option>}
              </select>
              {locId !== NEW_NAME && <span class="sub">Not listed? Add it on the map (Shoot locations → New), or as a name only (e.g. a championship years ago), or later from the scorecard.</span>}
            </label>
            {locId === NEW_NAME && <Field label="Shoot name" help="Shows on the scorecard; not on the map. You can put it on the map later from its location page.">
              <input maxLength={60} value={newName} onInput={e => setNewName((e.target as HTMLInputElement).value)} /></Field>}
          </Card>
          <Card cls="hi">
            <div class="stats">
              <div><b>{total}</b><span>Total</span></div>
              <div><b>{scored}/{countOk ? n : '–'}</b><span>targets scored</span></div>
              <div><b>{errors || '–'}</b><span>{errors === 1 ? 'problem' : 'problems'}</span></div>
            </div>
          </Card>
          <div class="row">
            <button class="filled" disabled={!valid || busy} onClick={() => (scored === n ? save(true) : setAsk('unfinished'))}>{existing ? 'Save' : 'Add round'}</button>
            <button disabled={busy} onClick={back}>Cancel</button>
          </div>
          <div class="sub">{type === 'WOODSMAN'
            ? 'Type each target\'s arrows: W wound, K kill, M miss (e.g. K, WK, MMK, WWW). A number is a target score entered by hand.'
            : 'Type each animal\'s points: 24, 20 or 16 on arrow 1; 14 or 10 on arrow 2; 8 or 4 on arrow 3; 0 for a miss. =N enters a whole target\'s score by hand. A Fun shot (target kind) takes any score: it counts in the total but not in stats. Enter or Tab moves on.'}</div>
        </div><div>
          {!countOk ? <Card><div class="sub">Enter the number of targets to get the score sheet.</div></Card> : (
            <div class="sheet">
              {[rows.slice(0, half), rows.slice(half)].map((part, p) => (
                <table class="card-table sheet-table">
                  <thead><tr><th>#</th>{type === 'BIG_GAME' && <th>Target</th>}<th>{type === 'WOODSMAN' ? 'Arrows' : 'Points'}</th><th class="num">Score</th></tr></thead>
                  <tbody>{part.map((r, k) => {
                    const i = p * half + k
                    const res = results[i]
                    const err = res && 'error' in res ? res.error : null
                    return (
                      <tr class={err ? 'bad' : ''} title={err ?? ''}>
                        <td>{i + 1}</td>
                        {type === 'BIG_GAME' && <td><select class="kind" value={kindOf(r)} onChange={e => setKind(i, (e.target as HTMLSelectElement).value)}>
                          {KINDS.map(o => <option value={o.value}>{o.label}</option>)}</select></td>}
                        <td><div class="cells">{r.cells.map((c, j) => (
                          <input class="cell" value={c} aria-label={`Target ${i + 1}${r.cells.length > 1 ? ` animal ${j + 1}` : ''}`}
                            disabled={cellDisabled(r, type, j)} placeholder={r.type === 'PREDATOR_PREY' ? (j === 0 ? 'Pred' : 'Prey') : ''}
                            inputMode={type === 'WOODSMAN' ? 'text' : 'numeric'} maxLength={4} onKeyDown={onKey as never}
                            onInput={e => setCell(i, j, (e.target as HTMLInputElement).value.toUpperCase())} />
                        ))}</div>{err && <div class="error small">{err}</div>}</td>
                        {/* 2.7.06: the score only when it isn't just the number typed (one animal, or =N) */}
                        <td class="num"><b>{res && 'target' in res && !(!isComplete(res.target) && totalPoints(res.target) === 0)
                          && !(r.cells.filter(c => c.trim()).length === 1 && r.cells.find(c => c.trim())!.trim().replace(/^=/, '') === String(totalPoints(res.target)))
                          ? totalPoints(res.target) : ''}</b></td>
                      </tr>
                    )
                  })}</tbody>
                </table>
              ))}
            </div>
          )}
        </div></div>
      </main>
      <Dialog open={ask === 'unfinished'} title="Not every target is scored" onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Keep typing</button><button onClick={() => save(false)}>Save as in progress</button>
          <button onClick={() => save(true)}>Save as finished</button></>}>
        {scored} of {n} targets have a score. A finished round with missing targets counts them as 0 and isn't used for personal bests.
      </Dialog>
    </>
  )
}
