import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Select, Seg, back } from './common'
import { getRound } from '../data/db'
import { updateDetails } from '../data/rounds'
import { AGE_NAMES, BOW_STYLES, CLASS_NAMES, MAX_START_TARGET, MAX_TARGETS, startTargetOk, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import { isComplete } from '../domain/scoring'

/**
 * A round's details: date, number of targets, class, age group and bow. Fewer targets removes the ones above
 * (with a warning if any are scored); more adds unscored ones and reopens a finished round.
 */
export function EditRound({ id }: { id: string }) {
  const [r, setR] = useState<Round | null>(null)
  const [date, setDate] = useState('')
  const [count, setCount] = useState('')
  const [cls, setCls] = useState<ArcherClass>('GENTS')
  const [age, setAge] = useState<AgeGroup>('ADULT')
  const [bow, setBow] = useState('LB')
  const [start, setStart] = useState('')
  const [confirm, setConfirm] = useState(false)
  useEffect(() => {
    getRound(id).then(x => {
      if (!x) return
      setR(x); setDate(x.shootDate); setCount(String(x.targetCount)); setCls(x.archerClass); setAge(x.ageGroup); setBow(x.bowStyle); setStart(x.startTarget ? String(x.startTarget) : '')
    })
  }, [id])
  if (!r) return <Bar title="Round details" />
  const n = Number(count)
  const countOk = Number.isInteger(n) && n >= 1 && n <= MAX_TARGETS
  const startN = start ? Number(start) : null
  const startOk = startN == null || startTargetOk(startN)
  const removed = countOk ? r.targets.filter(t => t.targetNo > n && isComplete(t)).map(t => t.targetNo) : []
  const reopens = countOk && n > r.targetCount && r.status === 'COMPLETED'
  const changed = date !== r.shootDate || n !== r.targetCount || cls !== r.archerClass || age !== r.ageGroup || bow !== r.bowStyle || startN !== (r.startTarget ?? null)
  const name = r.groupId ? (r.groupRole === 'SCORER' || !r.groupRole ? 'You' : r.groupName) : null
  const save = async () => { await updateDetails(r.id, date, n, cls, age, bow, startN); back() }
  return (
    <>
      <Bar title={name ? `Round details · ${name}` : 'Round details'}>
        <button disabled={!countOk || !startOk || !changed || !date} onClick={() => (removed.length ? setConfirm(true) : save())}>Save</button>
      </Bar>
      <main class="form">
        <Card title="Shoot" sub={r.groupId ? 'Group round: the date and number of targets change for everyone.' : undefined}>
          <Field label="Shoot date"><input type="date" value={date} onInput={e => setDate((e.target as HTMLInputElement).value)} /></Field>
          <Field label="Number of targets" help={count && !countOk ? `1 to ${MAX_TARGETS}`
            : removed.length ? `Removes scored target${removed.length === 1 ? '' : 's'} ${removed.join(', ')}`
            : reopens ? 'Adds unscored targets and reopens the round' : undefined}>
            <input inputMode="numeric" value={count} onInput={e => setCount((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} />
          </Field>
          <Field label="Starting target (optional)" help={!startOk ? `1 to ${MAX_START_TARGET}` : r.groupId ? 'The same for everyone in the group.' : 'The first target shot, if not 1.'}>
            <input inputMode="numeric" value={start} onInput={e => setStart((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} />
          </Field>
        </Card>
        <Card title="Archer" sub={name && name !== 'You' ? `Only changes ${name}'s round.` : 'Only changes this round, not your profile.'}>
          <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={setCls} />
          <Select<AgeGroup> label="Age group" value={age} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]} onChange={a => a && setAge(a)} />
          <Select label="Bow style" value={bow} options={BOW_STYLES.map(b => b.code)} optionLabel={c => `${BOW_STYLES.find(b => b.code === c)!.name} (${c})`} onChange={b => b && setBow(b)} />
        </Card>
        <div class="sub">Scores aren't changed. A finished round uploads again with the new details.</div>
      </main>
      <Dialog open={confirm} title="Remove scored targets?" onClose={() => setConfirm(false)}
        actions={<><button onClick={() => setConfirm(false)}>Cancel</button><button class="danger" onClick={() => { setConfirm(false); save() }}>Remove and save</button></>}>
        Going down to {n} targets removes the scores for target{removed.length === 1 ? '' : 's'} {removed.join(', ')}. This can't be undone.
      </Dialog>
    </>
  )
}
