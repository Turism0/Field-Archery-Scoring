import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Tabs, go, one, today, ukDate, useData } from './common'
import { finished } from '../data/rounds'
import { getProfile } from '../data/account'
import {
  availableBows, availableTypes, bowOrderOf, matchesFilter, periodLabel, periodOptions, resolveFilter, samePeriod, toggled, typeOrder,
  type StatsFilter,
} from '../domain/statsFilter'
import { ROUND_TYPE_NAMES, bowName, statsTotal, type Round, type RoundStats } from '../domain/round'
import { MIN_TARGETS_FOR_BEST_AVERAGE, bestAverages, bests, seasons, trend, type TrendPoint } from '../domain/stats'
import { handicapEntry, personalHandicaps } from '../domain/handicap'
import type { RoundType } from '../domain/scoring'

const perWhat = (t: RoundType) => (t === 'WOODSMAN' ? 'per target' : 'per animal')
const roundLine = (r: Round) => ukDate(r.shootDate) + (r.locationName ? ` · ${r.locationName}` : '')

const FILTER_KEY = 'statsFilter'
function loadFilter(): Partial<StatsFilter> | null {
  try { const s = localStorage.getItem(FILTER_KEY); return s ? JSON.parse(s) : null } catch { return null }
}
function saveFilter(f: StatsFilter) {
  try { localStorage.setItem(FILTER_KEY, JSON.stringify(f)) } catch { /* private window: just not remembered */ }
}

/**
 * Stats from your own finished rounds: bests, trends and seasons (calendar years). 3.2 (Pete): filtered by round type,
 * bow style and date range; the choice is remembered on this browser for next time.
 */
export function Stats() {
  const rounds = useData(finished)
  const [tab, setTab] = useState(0)
  const [profileBow, setProfileBow] = useState<string | null | undefined>(undefined)
  const [chosen, setChosen] = useState<Partial<StatsFilter> | null>(loadFilter)
  useEffect(() => { getProfile().then(p => setProfileBow(p.bowStyle ?? null), () => setProfileBow(null)) }, [])
  if (!rounds || profileBow === undefined) return <><Bar title="Stats" /></>
  const filter = resolveFilter(chosen, rounds, profileBow)
  const change = (f: StatsFilter) => { setChosen(f); saveFilter(f) }
  const now = today()
  const shown = rounds.filter(r => matchesFilter(filter, r, now))
  return (
    <>
      <Bar title="Stats" />
      <Tabs tabs={['Bests', 'Trends', 'Seasons']} value={tab} onChange={setTab} />
      <main>
        {!rounds.length ? <p class="sub" style="text-align:center">Finish a round to see your stats.</p> : <>
          <FilterBar rounds={rounds} filter={filter} onChange={change} />
          {tab === 0 ? <Bests rounds={shown} all={rounds} /> : tab === 1 ? <Trends rounds={shown} types={filter.types} /> : <Seasons rounds={shown} />}
        </>}
      </main>
    </>
  )
}

/** Round types and bow styles (either or both, several) as chips, and the date range. */
function FilterBar({ rounds, filter, onChange }: { rounds: Round[]; filter: StatsFilter; onChange: (f: StatsFilter) => void }) {
  const types = availableTypes(rounds), bows = availableBows(rounds)
  const now = today()
  const periods = periodOptions()
  const idx = periods.findIndex(p => samePeriod(p, filter.period))
  return (
    <div class="card low col" style="gap:8px">
      {types.length > 1 && <div class="row">{types.map(t => (
        <button class={`chip ${filter.types.includes(t) ? 'on' : ''}`} aria-pressed={filter.types.includes(t)}
          onClick={() => onChange({ ...filter, types: toggled(filter.types, t, typeOrder) })}>{ROUND_TYPE_NAMES[t]}</button>))}</div>}
      <div class="row">{bows.map(b => (
        <button class={`chip ${filter.bows.includes(b) ? 'on' : ''}`} aria-pressed={filter.bows.includes(b)} title={bowName(b)}
          onClick={() => onChange({ ...filter, bows: toggled(filter.bows, b, bowOrderOf) })}>{bows.length > 3 ? b : `${bowName(b)} (${b})`}</button>))}</div>
      <label class="field">Dates
        <select value={String(idx)} onChange={e => onChange({ ...filter, period: periods[Number((e.target as HTMLSelectElement).value)] })}>
          {periods.map((p, i) => <option value={String(i)}>{periodLabel(p, now)}</option>)}
        </select></label>
    </div>
  )
}

function Bests({ rounds, all }: { rounds: Round[]; all: Round[] }) {
  const list = bests(rounds)
  const avgs = bestAverages(rounds)
  const groups = new Map<string, typeof list>()
  for (const b of list) { const k = `${b.type}|${b.bowStyle}`; groups.set(k, [...(groups.get(k) ?? []), b]) }
  return <>
    {/* The handicap always uses your last 20 rounds, whatever the filters. */}
    <HandicapSummary rounds={all} />
    <div class="sub">Best totals for each round type, bow style and number of targets in these dates, from fully scored rounds. Best average counts rounds of {MIN_TARGETS_FOR_BEST_AVERAGE}+ targets.</div>
    {!groups.size && <p>No fully scored rounds match these filters.</p>}
    <div class="grid2">{[...groups.entries()].map(([k, l]) => {
      const [type, bow] = k.split('|') as [RoundType, string]
      const a = avgs.find(x => x.type === type && x.bowStyle === bow)
      return (
        <Card title={`${ROUND_TYPE_NAMES[type]} · ${bowName(bow)} (${bow})`}>
          {a && <button class="row between plain" onClick={() => go(`/card/${a.round.id}`)}>
            <span><b>Best average {perWhat(type)}</b><div class="sub">{roundLine(a.round)}</div></span><span class="num-big">{one(a.average)}</span></button>}
          {l.map(pb => (
            <button class="row between plain" onClick={() => go(`/card/${pb.best.id}`)}>
              <span><b>{pb.targetCount} targets</b><div class="sub">{roundLine(pb.best)}</div>
                <div class="sub">{pb.roundsShot} round{pb.roundsShot === 1 ? '' : 's'} · average {Math.round(pb.averageTotal)}</div></span>
              <span class="num-big">{statsTotal(pb.best)}</span>
            </button>
          ))}
        </Card>
      )
    })}</div>
  </>
}

/** Your personal handicap for each bow style (2.9; the details are on Handicaps). */
function HandicapSummary({ rounds }: { rounds: Round[] }) {
  const hs = personalHandicaps(rounds.map(handicapEntry).filter((e): e is NonNullable<typeof e> => !!e))
  return (
    <Card title="Handicap" cls="hi">
      {!hs.length ? <div>No handicap yet: it needs 3 finished Big Game rounds with every target scored, with the same bow style.</div>
        : hs.map(h => <div class="row between"><span>{bowName(h.bowStyle)}</span><b class="big">{h.handicap}</b></div>)}
      <button class="plain" style="align-self:flex-start;color:inherit;text-decoration:underline" onClick={() => go('/handicap')}>How it works, and the class multipliers ›</button>
    </Card>
  )
}

/** A trend for each round type chosen (3.2: the filters pick the bows and dates). */
function Trends({ rounds, types }: { rounds: Round[]; types: RoundType[] }) {
  return <>{types.map(t => <Trend rounds={rounds} type={t} heading={types.length > 1} />)}</>
}

function Trend({ rounds, type, heading }: { rounds: Round[]; type: RoundType; heading: boolean }) {
  const bows = new Set(rounds.filter(r => r.type === type).map(r => r.bowStyle))
  const bow = bows.size === 1 ? [...bows][0] : null
  const points = trend(rounds, type, null)
  return <>
    {heading && <h3 class="section">{ROUND_TYPE_NAMES[type]}</h3>}
    <div class="split"><div>
    <Card title={`Average ${perWhat(type)}`} sub="Each dot is a round; the line is the average of the last 5.">
      {points.length < 2 ? <div>Shoot at least two rounds of this kind (in these filters) to see a trend.</div> : <TrendChart points={points} max={type === 'WOODSMAN' ? 30 : 24} />}
    </Card>
    {points.length >= 2 && <TrendSummary points={points} />}
    </div><div>
    {points.length > 0 && <>
      <h3 class="section">Rounds (newest first)</h3>
      {[...points].reverse().map(p => (
        <button class="row between plain" onClick={() => go(`/card/${p.round.id}`)}>
          <span>{ukDate(p.date)}<div class="sub">{p.round.targetCount} targets · {statsTotal(p.round)} points{bow == null ? ` · ${p.round.bowStyle}` : ''}{p.round.locationName ? ` · ${p.round.locationName}` : ''}</div></span>
          <b>{one(p.average)}</b>
        </button>
      ))}
    </>}
    </div></div>
  </>
}

function TrendSummary({ points }: { points: TrendPoint[] }) {
  const avg = (xs: number[]) => xs.reduce((s, x) => s + x, 0) / xs.length
  const last = avg(points.slice(-5).map(p => p.average))
  const before = points.slice(0, -5).slice(-5).map(p => p.average)
  const change = before.length ? last - avg(before) : null
  return (
    <Card cls="hi"><div class="stats">
      <div><b>{one(last)}</b><span>Last {Math.min(5, points.length)}</span></div>
      {change != null && <div><b>{change >= 0 ? '+' : ''}{one(change)}</b><span>vs the {before.length} before</span></div>}
      <div><b>{one(avg(points.map(p => p.average)))}</b><span>All {points.length}</span></div>
    </div></Card>
  )
}

const dayNo = (iso: string) => Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)) / 86_400_000

/** Dots for each round (spaced by date) and a line for the rolling average. */
function TrendChart({ points, max }: { points: TrendPoint[]; max: number }) {
  const values = points.flatMap(p => [p.average, p.rolling])
  let lo = Math.max(0, Math.floor(Math.min(...values)) - 1)
  let hi = Math.min(max, Math.ceil(Math.max(...values)) + 1)
  if (hi - lo < 2) { lo = Math.max(0, lo - 1); hi = Math.min(max, hi + 1) }
  const first = dayNo(points[0].date), days = Math.max(1, dayNo(points[points.length - 1].date) - first)
  const W = 300, H = 160
  const x = (p: TrendPoint) => ((dayNo(p.date) - first) / days) * W
  const y = (v: number) => H * (1 - (v - lo) / (hi - lo))
  const mid = (hi + lo) / 2
  return (
    <div>
      <div class="row" style="flex-wrap:nowrap;align-items:stretch">
        <div class="axis"><span>{hi}</span><span>{mid % 1 ? one(mid) : mid}</span><span>{lo}</span></div>
        <svg viewBox={`-6 -6 ${W + 12} ${H + 12}`} class="chart" role="img" aria-label="Trend chart">
          {[0, H / 2, H].map(gy => <line x1={0} x2={W} y1={gy} y2={gy} class="grid" />)}
          {points.map(p => <circle cx={x(p)} cy={y(p.average)} r={3.5} class="dot" />)}
          <polyline points={points.map(p => `${x(p)},${y(p.rolling)}`).join(' ')} class="line" />
        </svg>
      </div>
      <div class="row between sub" style="padding-left:34px"><span>{ukDate(points[0].date)}</span><span>{ukDate(points[points.length - 1].date)}</span></div>
      <div class="row sub"><svg width="10" height="10"><circle cx="5" cy="5" r="4" class="dot" /></svg> Round
        <svg width="16" height="10"><line x1="0" x2="16" y1="5" y2="5" class="line" /></svg> Average of last 5</div>
    </div>
  )
}

function Seasons({ rounds }: { rounds: Round[] }) {
  if (!rounds.length) return <p>No finished rounds match these filters.</p>
  return <div class="grid2">{seasons(rounds).map(s => (
    <Card title={`${s.year} · ${ROUND_TYPE_NAMES[s.type]}`}
      sub={`${s.rounds} round${s.rounds === 1 ? '' : 's'} · ${s.targets} targets` + (s.locations ? ` · ${s.locations} location${s.locations === 1 ? '' : 's'}` : '') +
        (s.personalBests ? ` · ${s.personalBests} PB${s.personalBests === 1 ? '' : 's'}` : '')}>
      <div class="stats">
        <div><b>{s.stats.averagePerAnimal != null ? one(s.stats.averagePerAnimal) : '–'}</b><span>Average {perWhat(s.type)}</span></div>
        {s.type === 'BIG_GAME' && <div><b>{s.stats.innerKills}</b><span>Inner kills</span></div>}
        <div><b>{s.stats.kills}</b><span>Kills</span></div>
      </div>
      <MoreStats s={s.stats} />
      {s.bestRound && <button class="row between plain" onClick={() => go(`/card/${s.bestRound!.id}`)}>
        <span><b>Best round</b><div class="sub">{s.bestRound.targetCount} targets · {s.bestRound.bowStyle} · {roundLine(s.bestRound)}</div></span>
        <span class="num-big">{statsTotal(s.bestRound)}</span></button>}
      {s.byBow.length > 1 && s.byBow.map(b => (
        <div class="row between"><span>{bowName(b.bowStyle)} ({b.bowStyle})</span>
          <span class="sub">{b.rounds} round{b.rounds === 1 ? '' : 's'} · avg {b.average != null ? one(b.average) : '–'}</span></div>
      ))}
    </Card>
  ))}</div>
}

const pct = (v: number | null) => (v == null ? '–' : `${Math.round(v * 100)}%`)

/** Shares of the maximum and rates, as the app's scorecard. */
export function MoreStats({ s }: { s: RoundStats }) {
  return (
    <div class="card low">
      <div class="stats four">
        <div><b>{pct(s.shareOfMax)}</b><span>Of maximum</span></div>
        <div><b>{pct(s.firstArrowRate)}</b><span>1st arrow</span></div>
        <div><b>{pct(s.killRate)}</b><span>Kills</span></div>
        <div><b>{pct(s.missRate)}</b><span>Misses</span></div>
      </div>
      <div class="sub" style="text-align:center">{s.animalsScored} {s.maxPerAnimal === 24 ? 'animals' : 'targets'} scored · maximum {s.animalsScored * s.maxPerAnimal} points</div>
    </div>
  )
}

