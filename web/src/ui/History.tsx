import { getAccount } from '../data/account'
import { allRounds, getKv } from '../data/db'
import { RoundCard } from './Parts'
import { useState } from 'preact/hooks'
import { Bar, Dialog, Field, go, today, ukDate, useData, useDesktop } from './common'
import { ExportDialog } from './Export'
import { finished } from '../data/rounds'
import { ROUND_TYPE_NAMES, bowName } from '../domain/round'
import { bowOrder, inPeriod, periodLabel, personalBestIds, years, type Period } from '../domain/stats'
import { handicapEntry, net, personalHandicap } from '../domain/handicap'
import { totalPoints, type RoundType } from '../domain/scoring'

interface Filter { type?: RoundType; bow?: string; location?: string; period: Period }
const NONE: Filter = { period: { kind: 'all' } }

/** Finished rounds, newest first, with filters and PB marks; tap for the scorecard. */
export function History() {
  const rounds = useData(finished)
  const account = useData(getAccount)
  const lastSync = useData(() => getKv<string>('lastSync'))
  const sizes = useData(async () => {
    const c: Record<string, number> = {}
    for (const r of await allRounds()) if (r.groupId) c[r.groupId] = (c[r.groupId] ?? 0) + 1
    return c
  }) ?? {}
  const [f, setF] = useState<Filter>(NONE)
  const [dates, setDates] = useState<{ from?: string; to?: string } | null>(null)
  const [exporting, setExporting] = useState(false)
  const desktop = useDesktop()
  if (!rounds) return <Bar title="Score history" />
  const pbs = personalBestIds(rounds)
  // Net score with the personal handicap you had before each Big Game round (2.9).
  const entries = rounds.map(handicapEntry).filter((e): e is NonNullable<typeof e> => !!e)
  const netOf = (r: typeof rounds[number]) => {
    if (r.type !== 'BIG_GAME') return null
    const total = r.targets.reduce((x, t) => x + totalPoints(t), 0)
    const h = personalHandicap(entries, r.bowStyle, { shootDate: r.shootDate, order: r.createdUtc, bowStyle: r.bowStyle, points: total, targetCount: r.targetCount })
    return h ? { net: net(total, r.targetCount, h.handicap), handicap: h.handicap } : null
  }
  const now = today()
  const list = rounds.filter(r => (!f.type || r.type === f.type) && (!f.bow || r.bowStyle === f.bow) &&
    (!f.location || r.locationName === f.location) && inPeriod(f.period, r.shootDate, now))
  const types = [...new Set(rounds.map(r => r.type))]
  const bows = [...new Set(rounds.map(r => r.bowStyle))].sort((a, b) => bowOrder(a) - bowOrder(b))
  const locations = [...new Set(rounds.map(r => r.locationName).filter((x): x is string => !!x))].sort()
  const active = f.type || f.bow || f.location || f.period.kind !== 'all'
  const periodValue = f.period.kind === 'all' ? '' : f.period.kind === 'year' ? `y${f.period.year}` : f.period.kind === '12m' ? '12m' : 'dates'
  const fmt = (p: { from?: string; to?: string }) =>
    p.from && p.to ? `${ukDate(p.from)} – ${ukDate(p.to)}` : p.from ? `From ${ukDate(p.from)}` : p.to ? `To ${ukDate(p.to)}` : 'Dates…'

  return (
    <>
      <Bar title="Score history">
        {desktop && rounds.length > 0 && <button class="tonal" disabled={!list.length} onClick={() => setExporting(true)}>⤓ Export</button>}
      </Bar>
      <main>
        {!rounds.length ? <div style="text-align:center;padding-top:48px"><h2 style="font-weight:400">No finished rounds yet</h2><p class="sub">Rounds appear here once you finish them.</p></div> : <>
          {account && <div class="sub" style="text-align:center">{lastSync ? `Last synced ${new Date(lastSync).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}` : 'Not synced yet'}</div>}
          <div class="filters">
            {(types.length > 1 || f.type) && <select class={f.type ? 'on' : ''} value={f.type ?? ''} onChange={e => setF({ ...f, type: ((e.target as HTMLSelectElement).value || undefined) as RoundType | undefined })}>
              <option value="">Type: All</option>{types.map(t => <option value={t}>{ROUND_TYPE_NAMES[t]}</option>)}</select>}
            <select class={f.bow ? 'on' : ''} value={f.bow ?? ''} onChange={e => setF({ ...f, bow: (e.target as HTMLSelectElement).value || undefined })}>
              <option value="">Bow: All</option>{bows.map(b => <option value={b}>{bowName(b)} ({b})</option>)}</select>
            {(locations.length > 0 || f.location) && <select class={f.location ? 'on' : ''} value={f.location ?? ''} onChange={e => setF({ ...f, location: (e.target as HTMLSelectElement).value || undefined })}>
              <option value="">Location: All</option>{locations.map(l => <option value={l}>{l}</option>)}</select>}
            <select class={f.period.kind !== 'all' ? 'on' : ''} value={periodValue} onChange={e => {
              const v = (e.target as HTMLSelectElement).value
              if (v === 'dates') setDates(f.period.kind === 'between' ? f.period : {})
              else setF({ ...f, period: v === '' ? { kind: 'all' } : v === '12m' ? { kind: '12m' } : { kind: 'year', year: Number(v.slice(1)) } })
            }}>
              <option value="">When: All time</option><option value="12m">Last 12 months</option>
              {years(rounds.map(r => r.shootDate)).map(y => <option value={`y${y}`}>{y}</option>)}
              <option value="dates">{f.period.kind === 'between' ? fmt(f.period) : 'Choose dates…'}</option>
            </select>
          </div>
          {active && <div class="row between"><span class="sub">Showing {list.length} of {rounds.length} round{rounds.length === 1 ? '' : 's'}{f.period.kind !== 'all' && f.period.kind !== 'between' ? ` · ${periodLabel(f.period)}` : ''}</span>
            <button onClick={() => setF(NONE)}>Clear filters</button></div>}
          {!list.length && <p class="sub" style="text-align:center">No rounds match these filters.</p>}
          <div class="grid2">{list.map(r => (
            <RoundCard r={r} showSync={!!account} pb={pbs.has(r.id)} groupSize={r.groupId ? sizes[r.groupId] ?? 1 : 1} net={netOf(r)} onClick={() => go(`/card/${r.id}`)} />
          ))}</div>
        </>}
      </main>
      <ExportDialog open={exporting} rounds={list} onClose={() => setExporting(false)}
        what={active ? `The ${list.length} round${list.length === 1 ? '' : 's'} matching your filters.` : `All ${list.length} finished round${list.length === 1 ? '' : 's'}.`} />
      <Dialog open={!!dates} title="Rounds between" onClose={() => setDates(null)}
        actions={<><button onClick={() => setDates(null)}>Cancel</button>
          <button onClick={() => { setF({ ...f, period: { kind: 'between', ...dates } }); setDates(null) }}>Show</button></>}>
        <Field label="From (optional)"><input type="date" value={dates?.from ?? ''} onInput={e => setDates({ ...dates, from: (e.target as HTMLInputElement).value || undefined })} /></Field>
        <Field label="To (optional)"><input type="date" value={dates?.to ?? ''} onInput={e => setDates({ ...dates, to: (e.target as HTMLInputElement).value || undefined })} /></Field>
      </Dialog>
    </>
  )
}
