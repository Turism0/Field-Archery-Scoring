// Stats → Analysis (3.2.17, Pete): form and consistency, arrows, hits (with a chart over time), target types, start and
// finish, compared with others, by location and by weather. The same sections as the app's Analysis tab.
import { getEquipment, type EquipmentStore } from '../data/equipment'
import { arrowLabel, bowLabel } from '../domain/equipment'
import { Card, go, one, useData } from './common'
import { ROUND_TYPE_NAMES, bowName, CLASS_NAMES, AGE_NAMES, type Round } from '../domain/round'
import { TARGET_TYPE_NAMES, type RoundType } from '../domain/scoring'
import {
  arrowCounts, byEquipment, byLocation, byTargetType, comparedText, consistency, form, hitCounts, hitsOverTime, recentRounds, roundAverages, streak, thirds,
  weatherSplits, type ArrowCounts, type EquipmentSummary, type HitCounts,
} from '../domain/analysis'
import { compareKey, compareWithOthers, weatherMap } from '../data/weather'
import { getAccount } from '../data/account'

const per = (t: RoundType) => (t === 'WOODSMAN' ? 'per target' : 'per animal')
const pct = (n: number, of: number) => (of ? `${Math.round((n / of) * 100)}%` : '–')

/** Colours for hits, the same in light and dark. */
const HIT_COLOURS = { innerKill: '#2f6b3c', kill: '#7fb88a', wound: '#d9a441', miss: '#9a9a9a' }

export function Analysis({ rounds, types }: { rounds: Round[]; types: RoundType[] }) {
  const weather = useData(weatherMap)
  const kit = useData(getEquipment)
  return <>{types.map(t => {
    const list = rounds.filter(r => r.type === t)
    return <>
      {types.length > 1 && <h3 class="section">{ROUND_TYPE_NAMES[t]}</h3>}
      {!list.length ? <p class="sub">No finished {ROUND_TYPE_NAMES[t]} rounds match these filters.</p>
        : <TypeAnalysis rounds={list} type={t} weather={weather ?? {}} kit={kit} />}
    </>
  })}</>
}

function TypeAnalysis({ rounds, type, weather, kit }: { rounds: Round[]; type: RoundType; weather: Record<string, any>; kit?: EquipmentStore }) {
  const arrows = arrowCounts(rounds)
  const all = hitCounts(rounds), recent = hitCounts(recentRounds(rounds, 10))
  const overTime = hitsOverTime(rounds)
  const c = consistency(rounds), f = form(rounds, type), s = streak(rounds)
  const th = thirds(rounds)
  const locs = byLocation(rounds)
  const splits = weatherSplits(rounds, r => weather[r.id])
  const withWeather = rounds.filter(r => weather[r.id]).length
  const woodsman = type === 'WOODSMAN'
  return <div class="split"><div>
    <Card title="Form and consistency" cls="hi">
      {!c ? <div>Finish at least 3 rounds (in these filters) to see how consistent you are.</div> : <>
        <div class="stats">
          <div><b>{one(c.average)}</b><span>Average {per(type)}</span></div>
          <div><b>±{one(c.spread)}</b><span>Usual spread</span></div>
          <div><b>{f ? (f.direction === 'UP' ? '▲ Up' : f.direction === 'DOWN' ? '▼ Down' : '● Steady') : '–'}</b><span>Form</span></div>
        </div>
        <div class="sub">Most of your rounds are within about {one(c.spread)} of {one(c.average)} {per(type)}.
          {f && ` Your last 5 average ${one(f.recent)}, against ${one(f.before)} before them (${f.change >= 0 ? '+' : ''}${one(f.change)}).`}
          {s && (s.current > 0
            ? ` You're on a run of ${s.current} round${s.current === 1 ? '' : 's'} at or above your average (best run: ${s.best}).`
            : ` Your best run at or above your average is ${s.best} round${s.best === 1 ? '' : 's'}.`)}</div>
      </>}
    </Card>

    <Card title={woodsman ? 'Which arrow killed' : 'Which arrow scored'} sub={woodsman ? 'Targets with the arrows recorded.' : 'Animals with the arrows recorded (not typed-in scores).'}>
      <ArrowBars a={arrows} woodsman={woodsman} />
    </Card>

    <Card title="Hits" sub={woodsman ? 'Each target: killed, wounded only, or nothing.' : 'The scoring hit on each animal, or a miss.'}>
      {rounds.length > 10 && <HitBar label="Last 10 rounds" h={recent} woodsman={woodsman} />}
      <HitBar label={`All ${rounds.length} rounds`} h={all} woodsman={woodsman} />
      <HitLegend woodsman={woodsman} />
      {overTime.length >= 2 && <>
        <b style="margin-top:8px">Over time</b>
        {overTime.map(p => <HitBar label={p.label} h={p.hits} woodsman={woodsman} compact />)}
      </>}
    </Card>

    {!woodsman && (() => {
      const types = byTargetType(rounds)
      return types.length > 1 && <Card title="By target type" sub="Average per animal on each kind of target.">
        {types.map(t => <div class="row between"><span>{TARGET_TYPE_NAMES[t.type]}<span class="sub"> · {t.targets} target{t.targets === 1 ? '' : 's'}</span></span><b>{one(t.average)}</b></div>)}
      </Card>
    })()}

    <Card title="Start, middle and finish" sub={`Average ${per(type)} in each third of your rounds, in the order you shot them.`}>
      <div class="stats">
        <div><b>{th.start != null ? one(th.start) : '–'}</b><span>First third</span></div>
        <div><b>{th.middle != null ? one(th.middle) : '–'}</b><span>Middle</span></div>
        <div><b>{th.finish != null ? one(th.finish) : '–'}</b><span>Last third</span></div>
      </div>
    </Card>
  </div><div>
    <Compared rounds={rounds} type={type} />

    <Card title="By location" sub={`Average and best ${per(type)} at each ground.`}>
      {!locs.length ? <div>None of these rounds have a shoot location.</div>
        : locs.map(l => <button class="row between plain" onClick={() => go(`/card/${l.bestRound.id}`)}>
          <span><b>{l.name}</b><div class="sub">{l.rounds} round{l.rounds === 1 ? '' : 's'} · best {one(l.best)}</div></span><b>{one(l.average)}</b></button>)}
    </Card>

    {/* 3.2.24 (Pete): your bows and arrows compared (once you've used more than one). */}
    <EquipmentCard rounds={rounds} type={type} kit={kit} />

    <Card title="Weather" sub={`Your average ${per(type)} in different weather. ${withWeather} of these ${rounds.length} rounds have weather (rounds at a location with a map pin). Weather data by Open-Meteo.com.`}>
      {withWeather < 2 ? <div>Not enough rounds with weather yet.</div>
        : splits.map(sp => <div class="row between">
          <span><b>{sp.label}</b></span>
          <span class="sub">{sp.yesLabel}: {sp.yes.average != null ? `${one(sp.yes.average)} (${sp.yes.rounds})` : '–'} · {sp.noLabel}: {sp.no.average != null ? `${one(sp.no.average)} (${sp.no.rounds})` : '–'}</span>
        </div>)}
    </Card>
  </div></div>
}

function ArrowBars({ a, woodsman }: { a: ArrowCounts; woodsman: boolean }) {
  if (!a.total) return <div>No rounds with the arrows recorded.</div>
  const rows = [['1st arrow', a.first], ['2nd arrow', a.second], ['3rd arrow', a.third], [woodsman ? 'No kill' : 'Missed', a.none]] as const
  const max = Math.max(...rows.map(r => r[1]), 1)
  return <div class="col" style="gap:6px">{rows.map(([label, n], i) => (
    <div class="row" style="flex-wrap:nowrap;gap:8px">
      <span style="width:88px;flex:none;white-space:nowrap">{label}</span>
      <span class="grow" style="background:var(--surface-c);border-radius:6px;height:18px;overflow:hidden">
        <span style={`display:block;height:100%;width:${(n / max) * 100}%;background:${i === 3 ? HIT_COLOURS.miss : 'var(--primary)'}`} /></span>
      <b style="width:44px;text-align:right;flex:none">{pct(n, a.total)}</b>
    </div>))}
    <div class="sub">{a.total} {woodsman ? 'targets' : 'animals'}</div>
  </div>
}

function HitBar({ label, h, woodsman, compact }: { label: string; h: HitCounts; woodsman: boolean; compact?: boolean }) {
  if (!h.total) return null
  const parts = (woodsman ? [['kill', h.kill], ['wound', h.wound], ['miss', h.miss]] : [['innerKill', h.innerKill], ['kill', h.kill], ['wound', h.wound], ['miss', h.miss]]) as [keyof typeof HIT_COLOURS, number][]
  return <div class="col" style="gap:2px">
    <div class="row between"><span class={compact ? 'sub' : ''}>{label}</span>
      <span class="sub">{parts.map(([, n]) => pct(n, h.total)).join(' / ')}</span></div>
    <div style={`display:flex;height:${compact ? 10 : 16}px;border-radius:6px;overflow:hidden`} role="img"
      aria-label={parts.map(([k, n]) => `${k} ${pct(n, h.total)}`).join(', ')}>
      {parts.map(([k, n]) => n > 0 && <span style={`flex:${n};background:${HIT_COLOURS[k]}`} />)}
    </div>
  </div>
}

function HitLegend({ woodsman }: { woodsman: boolean }) {
  const items: [keyof typeof HIT_COLOURS, string][] = woodsman
    ? [['kill', 'Killed'], ['wound', 'Wounded only'], ['miss', 'Nothing']]
    : [['innerKill', 'Inner kill'], ['kill', 'Kill'], ['wound', 'Wound'], ['miss', 'Miss']]
  return <div class="row sub" style="gap:10px">{items.map(([k, l]) => <span class="row" style="gap:4px">
    <span class="legend" style={`background:${HIT_COLOURS[k]}`} />{l}</span>)}</div>
}

/** Compared with everyone else's rounds of the same kind (from the server; needs a sign-in and signal). */
function Compared({ rounds, type }: { rounds: Round[]; type: RoundType }) {
  const account = useData(getAccount)
  const groups = new Map<string, Round[]>()
  for (const r of rounds) groups.set(compareKey(r), [...(groups.get(compareKey(r)) ?? []), r])
  const wanted = [...groups.entries()].map(([key, l]) => {
    const avgs = roundAverages(l).map(x => x.average)
    return { key, sample: l[0], average: avgs.length ? avgs.reduce((s, x) => s + x, 0) / avgs.length : null }
  }).filter((g): g is { key: string; sample: Round; average: number } => g.average != null)
  const sig = wanted.map(g => `${g.key}:${g.average.toFixed(2)}`).join(',')
  const result = useData(async () => (account ? compareWithOthers(wanted.map(g => ({ key: g.key, average: g.average }))).catch(() => null) : null), [sig, account?.token])
  return <Card title="Compared with others" sub={`Your average ${per(type)} against everyone else's rounds of the same kind (no names).`}>
    {!account ? <div>Sign in to compare with other archers.</div>
      : result === undefined ? <div class="sub">Loading…</div>
      : result === null ? <div>Needs signal (and the server).</div>
      : wanted.map(g => {
        const who = `${CLASS_NAMES[g.sample.archerClass]} ${AGE_NAMES[g.sample.ageGroup]} ${bowName(g.sample.bowStyle)}`
        const res = result.find(x => x.key === g.key)
        const text = res && comparedText(res.betterThan, res.rounds, who)
        return <div class="row between"><span><b>{who}</b><div class="sub">You: {one(g.average)}</div></span>
          <span class="sub" style="text-align:right">{text ?? 'Not enough rounds from other archers yet'}</span></div>
      })}
  </Card>
}

function EquipmentCard({ rounds, type, kit }: { rounds: Round[]; type: RoundType; kit?: EquipmentStore }) {
  const byBow = byEquipment(rounds, r => r.bowId), byArrows = byEquipment(rounds, r => r.arrowId)
  if (byBow.length < 2 && byArrows.length < 2) return null
  const bowLabelOf = (id: string) => { const b = kit?.bows.find(x => x.id === id); return b ? bowLabel(b) : 'Unknown bow' }
  const arrowLabelOf = (id: string) => { const a = kit?.arrows.find(x => x.id === id); return a ? arrowLabel(a) : 'Unknown arrows' }
  const rows = (title: string, list: EquipmentSummary[], name: (id: string) => string) => list.length < 2 ? null : <>
    <h3 class="small">{title}</h3>
    {list.map(e => <button class="row between plain" onClick={() => go(`/card/${e.bestRound.id}`)}>
      <span><b>{name(e.id)}</b><div class="sub">{e.rounds} round{e.rounds === 1 ? '' : 's'} · best {one(e.best)}{e.killRate != null ? ` · kills ${Math.round(e.killRate * 100)}%` : ''}</div></span>
      <b>{one(e.average)}</b></button>)}
  </>
  return (
    <Card title="By equipment" sub={`Average and best ${per(type)} with each bow and set of arrows.`}>
      {rows('Bows', byBow, bowLabelOf)}
      {rows('Arrows', byArrows, arrowLabelOf)}
    </Card>
  )
}
