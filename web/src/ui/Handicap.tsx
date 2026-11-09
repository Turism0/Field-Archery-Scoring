import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Field, Select, Seg, useData } from './common'
import { getProfile } from '../data/account'
import { getModel, refreshModel } from '../data/handicap'
import { AGE_NAMES, BOW_STYLES, CLASS_NAMES, ageGroupFor, type AgeGroup, type ArcherClass } from '../domain/round'
import { convert, handicapPoints, type HArcher } from '../domain/handicap'
import { today } from './common'

const CLASSES: [string, ArcherClass, AgeGroup][] = [
  ['Gents', 'GENTS', 'ADULT'], ['Ladies', 'LADIES', 'ADULT'], ['Jr Boys', 'GENTS', 'JUNIOR'], ['Jr Girls', 'LADIES', 'JUNIOR'],
  ['U12 Boys', 'GENTS', 'CUB'], ['U12 Girls', 'LADIES', 'CUB'],
]

/** A score with your bow and class, its handicap points, and the same performance for every bow style and class. */
export function HandicapCalculator() {
  const model = useData(getModel)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  const [score, setScore] = useState('')
  const [targets, setTargets] = useState('36')
  const [cls, setCls] = useState<ArcherClass>('GENTS')
  const [age, setAge] = useState<AgeGroup>('ADULT')
  const [bow, setBow] = useState<string>('LB')
  useEffect(() => {
    getProfile().then(p => {
      if (p.archerClass) setCls(p.archerClass)
      if (p.bowStyle) setBow(p.bowStyle)
      const a = p.ageGroupManual ?? (p.dateOfBirth ? ageGroupFor(p.dateOfBirth, today()) : null)
      if (a) setAge(a)
    })
  }, [])

  if (model === undefined) return <Bar title="Handicap calculator" />
  const n = Number(targets), sc = Number(score)
  const max = 24 * n
  const problem = score && n > 0 && sc > max ? `The most for ${n} targets is ${max}.` : null
  const ready = model && score !== '' && Number.isInteger(n) && n > 0 && !problem
  const from: HArcher = { bowStyle: bow, archerClass: cls, ageGroup: age }

  return (
    <>
      <Bar title="Handicap calculator" />
      <main>
        {!model ? (
          <Card title="The handicap hasn't been downloaded yet" sub="It comes from the server once (no sign-in needed), then works with no signal.">
            {failed && <div class="error">Couldn't download it. Check you have signal and try again.</div>}
            <button class="tonal" disabled={busy} onClick={async () => { setBusy(true); setFailed(!(await refreshModel())); setBusy(false) }}>{busy ? 'Downloading…' : 'Download now'}</button>
          </Card>
        ) : <>
          <Card title="Your score" sub="A whole round or one day: the score and how many targets it was out of.">
            <div class="row" style="flex-wrap:nowrap">
              <Field label="Score"><input inputMode="numeric" value={score} onInput={e => setScore((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 4))} /></Field>
              <Field label="Targets"><input inputMode="numeric" value={targets} onInput={e => setTargets((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} /></Field>
            </div>
            {problem && <div class="error">{problem}</div>}
            <div class="row">{[18, 20, 36, 40].map(t => <button class={`chip ${n === t ? 'on' : ''}`} onClick={() => setTargets(String(t))}>{t}</button>)}</div>
            <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={setCls} />
            <Select<AgeGroup> label="Age group" value={age} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]} onChange={a => a && setAge(a)} />
            <Select label="Bow style" value={bow} options={BOW_STYLES.map(b => b.code)} optionLabel={c => `${BOW_STYLES.find(b => b.code === c)!.name} (${c})`} onChange={b => b && setBow(b)} />
          </Card>
          {ready && <>
            <Card cls="hi">
              <div class="big" style="text-align:center">Handicap {Math.round(handicapPoints(model, sc, max, from, model.dataThroughYear))}</div>
              <div style="text-align:center">out of 1000 – the same for the same performance with any bow and class</div>
            </Card>
            <h3 class="section">The same performance with every bow style and class</h3>
            <div class="card low scroll-x" style="padding:8px 0">
              <table class="equiv">
                <thead><tr><th>Bow</th>{CLASSES.map(c => <th>{c[0]}</th>)}</tr></thead>
                <tbody>{BOW_STYLES.map(b => (
                  <tr><td style={b.code === bow ? 'font-weight:700' : ''}>{b.name}</td>
                    {CLASSES.map(([, c, a]) => {
                      const mine = b.code === bow && c === cls && a === age
                      return <td class={mine ? 'mine' : ''}>{Math.round(convert(model, sc, max, from, model.dataThroughYear, { bowStyle: b.code, archerClass: c, ageGroup: a }))}</td>
                    })}</tr>
                ))}</tbody>
              </table>
            </div>
            <div class="sub">From the NFAS Nationals and 3D Championships since 2002 (paper faces and 3D targets averaged), up to {model.dataThroughYear}. Equal scores here mean the same standing among archers with that bow and class.</div>
          </>}
        </>}
      </main>
    </>
  )
}
