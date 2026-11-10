import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Field, Select, Seg, Tabs, go, today, ukDate, useData } from './common'
import { getProfile } from '../data/account'
import { getModel, refreshModel } from '../data/handicap'
import { finished } from '../data/rounds'
import { AGE_NAMES, BOW_STYLES, CLASS_NAMES, ageGroupFor, bowName, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import {
  CLASS_COLUMNS, MAX_ROUNDS, MIN_ROUNDS, PAR, PAR_TARGETS, applyMultiplier, convert, handicapEntry, multiplier, personalHandicaps, scaled,
  type HArcher, type HandicapModel,
} from '../domain/handicap'

/**
 * Your bow style and class from the profile, or null when it doesn't say (2.9.01: nothing is highlighted then; it used
 * to fall back to Longbow Gents, which looked like yours). The calculator still starts on Longbow Gents Adult.
 */
function useMe(): HArcher | null {
  const [me, setMe] = useState<HArcher | null>(null)
  useEffect(() => {
    getProfile().then(p => {
      const age = p.ageGroupManual ?? (p.dateOfBirth ? ageGroupFor(p.dateOfBirth, today()) : null)
      setMe(p.bowStyle && p.archerClass && age ? { bowStyle: p.bowStyle, archerClass: p.archerClass, ageGroup: age } : null)
    })
  }, [])
  return me
}

/**
 * Handicaps (2.9, Pete): your personal handicap for each bow style (like golf, par 960 off 40 targets), the class
 * multipliers for a handicap competition, and the calculator (a score as every other bow style and class).
 */
export function Handicaps() {
  const [tab, setTab] = useState(0)
  const model = useData(getModel)
  const rounds = useData(finished)
  const me = useMe()
  return (
    <>
      <Bar title="Handicaps" />
      <Tabs tabs={['My handicap', 'Multipliers', 'Calculator']} value={tab} onChange={setTab} />
      <main>
        {tab === 0 && <MyHandicap rounds={rounds ?? []} />}
        {tab === 1 && <Multipliers model={model} me={me} />}
        {tab === 2 && <Calculator model={model} me={me} />}
      </main>
    </>
  )
}

/** Your personal handicap for each bow style, with the rounds that count. */
export function MyHandicap({ rounds }: { rounds: Round[] }) {
  const entries = rounds.map(handicapEntry).filter((e): e is NonNullable<typeof e> => !!e)
  const hs = personalHandicaps(entries)
  const byId = new Map(rounds.map(r => [r.createdUtc + r.shootDate + r.bowStyle, r]))
  const short = [...new Set(entries.map(e => e.bowStyle))].filter(b => !hs.some(h => h.bowStyle === b))
  return <div class="split"><div>
    {!hs.length && <Card title="No handicap yet" cls="hi">
      <div>A handicap needs {MIN_ROUNDS} finished Big Game rounds with every target scored, all with the same bow style.</div>
      {short.map(b => <div class="sub" style="color:inherit">{bowName(b)}: {entries.filter(e => e.bowStyle === b).length} of {MIN_ROUNDS}</div>)}
    </Card>}
    {hs.map(h => (
      <Card title={bowName(h.bowStyle)} cls="hi">
        <div class="row between" style="flex-wrap:nowrap"><div>
          <div style="color:inherit">Usual score {Math.round(h.usual)} off {PAR_TARGETS} targets: the best {h.counted.length} of your last {h.considered} {h.bowStyle} round{h.considered === 1 ? '' : 's'}.</div></div>
          <span style="text-align:center"><span class="huge">{h.handicap}</span><span class="sub" style="display:block;color:inherit">handicap</span></span></div>
        {h.counted.map(e => {
          const r = byId.get(e.order + e.shootDate + e.bowStyle)
          return <button class="row between plain small" onClick={() => r && go(`/card/${r.id}`)}>
            <span>{ukDate(e.shootDate)} · {e.points} off {e.targetCount}</span><b>{Math.round(scaled(e))}</b></button>
        })}
      </Card>
    ))}
    {short.length > 0 && hs.length > 0 && <div class="sub">{short.map(b => `${bowName(b)}: ${entries.filter(e => e.bowStyle === b).length} of ${MIN_ROUNDS} rounds`).join(' · ')}</div>}
  </div><div>
    <Card title="How it works">
      <div>Like golf, but par is {PAR}: the most you can score on a {PAR_TARGETS}-target course.</div>
      <div>Your <b>usual score</b> is the average of your best 40% (at least {MIN_ROUNDS}) of your last {MAX_ROUNDS} finished, fully scored Big Game rounds with that bow – the best 8 of 20 once you have 20. Rounds on other numbers of targets are scaled to {PAR_TARGETS}.</div>
      <div>Your <b>handicap</b> is par minus your usual score. Each bow style has its own.</div>
      <div>Your <b>net score</b> is your score plus your handicap (scaled to the targets shot: on 36 targets, 36/40 of it). A usual round nets about par, so in a handicap competition the highest net score wins whatever anyone's standard. Scorecards and Score history show the net score with the handicap you had before each round.</div>
    </Card>
  </div></div>
}

/** The class multipliers for a handicap competition (score × multiplier). */
function Multipliers({ model, me }: { model: HandicapModel | null | undefined; me: HArcher | null }) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  if (model === undefined) return null
  if (!model || model.factors['BASE:ALL'] === undefined) return (
    <Card title={model ? 'The multipliers need the latest handicap' : "The handicap hasn't been downloaded yet"} sub="It comes from the server (no sign-in needed), then works with no signal.">
      {failed && <div class="error">Couldn't download it. Check you have signal and try again, or the server may need updating.</div>}
      <button class="tonal" style="align-self:flex-start" disabled={busy} onClick={async () => { setBusy(true); setFailed(!(await refreshModel())); setBusy(false) }}>{busy ? 'Downloading…' : 'Download now'}</button>
    </Card>
  )
  return <>
    <div class="sub">For a handicap competition: each archer's score × the multiplier for their bow style and class. Highest wins.</div>
    <div class="card low scroll-x" style="padding:8px 0">
      <table class="equiv">
        <thead><tr><th>Bow</th>{CLASS_COLUMNS.map(c => <th>{c[0]}</th>)}</tr></thead>
        <tbody>{BOW_STYLES.map(b => (
          <tr><td style={b.code === me?.bowStyle ? 'font-weight:700' : ''}>{b.name}</td>
            {CLASS_COLUMNS.map(([, c, a]) => {
              const mine = !!me && b.code === me.bowStyle && c === me.archerClass && a === me.ageGroup
              return <td class={mine ? 'mine' : ''}>{multiplier(model, { bowStyle: b.code, archerClass: c, ageGroup: a })!.toFixed(2)}</td>
            })}</tr>
        ))}</tbody>
      </table>
    </div>
    <div class="sub">Each multiplier is the maximum score ÷ a typical score for that bow style and class – an average archer at recent NFAS Nationals and 3D Championships (paper faces and 3D averaged, results up to {model.dataThroughYear}) – so a typical archer of any class comes out at about the maximum. Big Game only. They change a little as new results come in each year.</div>
  </>
}

/** A score with your bow and class, × your multiplier, and the same performance for every bow style and class. */
function Calculator({ model, me }: { model: HandicapModel | null | undefined; me: HArcher | null }) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  const [score, setScore] = useState('')
  const [targets, setTargets] = useState('36')
  const [cls, setCls] = useState<ArcherClass>('GENTS')
  const [age, setAge] = useState<AgeGroup>('ADULT')
  const [bow, setBow] = useState<string>('LB')
  // Start on your own bow and class when the profile says (else Longbow Gents Adult, just a starting point).
  useEffect(() => { if (me) { setCls(me.archerClass); setAge(me.ageGroup); setBow(me.bowStyle) } }, [me?.bowStyle, me?.archerClass, me?.ageGroup])

  if (model === undefined) return null
  const n = Number(targets), sc = Number(score)
  const max = 24 * n
  const problem = score && n > 0 && sc > max ? `The most for ${n} targets is ${max}.` : null
  const ready = model && score !== '' && Number.isInteger(n) && n > 0 && !problem
  const from: HArcher = { bowStyle: bow, archerClass: cls, ageGroup: age }
  const mult = model ? multiplier(model, from) : null

  if (!model) return (
    <Card title="The handicap hasn't been downloaded yet" sub="It comes from the server once (no sign-in needed), then works with no signal.">
      {failed && <div class="error">Couldn't download it. Check you have signal and try again.</div>}
      <button class="tonal" disabled={busy} onClick={async () => { setBusy(true); setFailed(!(await refreshModel())); setBusy(false) }}>{busy ? 'Downloading…' : 'Download now'}</button>
    </Card>
  )
  return <div class="split"><div>
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
    {ready && mult != null && <Card cls="hi">
      <div class="big" style="text-align:center">{applyMultiplier(sc, mult)}</div>
      <div style="text-align:center">{sc} × {mult.toFixed(2)}, the {CLASS_NAMES[cls]} {AGE_NAMES[age]} {bowName(bow)} multiplier</div>
    </Card>}
  </div><div>
    {ready && <>
      <h3 class="section">The same performance with every bow style and class</h3>
      <div class="card low scroll-x" style="padding:8px 0">
        <table class="equiv">
          <thead><tr><th>Bow</th>{CLASS_COLUMNS.map(c => <th>{c[0]}</th>)}</tr></thead>
          <tbody>{BOW_STYLES.map(b => (
            <tr><td style={b.code === bow ? 'font-weight:700' : ''}>{b.name}</td>
              {CLASS_COLUMNS.map(([, c, a]) => {
                const mine = b.code === bow && c === cls && a === age
                return <td class={mine ? 'mine' : ''}>{Math.round(convert(model, sc, max, from, model.dataThroughYear, { bowStyle: b.code, archerClass: c, ageGroup: a }))}</td>
              })}</tr>
          ))}</tbody>
        </table>
      </div>
      <div class="sub">From the NFAS Nationals and 3D Championships since 2002 (paper faces and 3D targets averaged), up to {model.dataThroughYear}. Equal scores here mean the same standing among archers with that bow and class.</div>
    </>}
  </div></div>
}
