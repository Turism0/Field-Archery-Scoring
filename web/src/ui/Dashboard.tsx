import { useState } from 'preact/hooks'
import { Bar, Card, go, one, ukDate, useData } from './common'
import { getAccount, isAdmin } from '../data/account'
import { finished, inProgress } from '../data/rounds'
import { getKv, setKv } from '../data/db'
import { getModel } from '../data/handicap'
import { ROUND_TYPE_NAMES, bowName, classLine, locationStatus, roundTotal, targetsComplete, targetsLine } from '../domain/round'
import { handicapRound, rating, roundPoints } from '../domain/handicap'
import { bests, personalBestIds, seasons } from '../domain/stats'
import { normalised, type HomeLayout } from '../domain/homeLayout'
import { Arrange } from './Home'

/**
 * Home on a wide screen (2.6.02, Pete): the computer is for typing in rounds shot on paper, fixing locations,
 * looking at stats and friends – not for scoring in the woods.
 */
export function Dashboard() {
  const account = useData(getAccount)
  const going = useData(inProgress) ?? []
  const done = useData(finished)
  const offers = useData(() => getKv<number>('offerCount')) ?? 0
  const duplicates = useData(() => getKv<number>('duplicateLocations')) ?? 0
  const stored = useData(() => getKv<HomeLayout>('homeLayout'))
  const model = useData(getModel)
  const [editing, setEditing] = useState<HomeLayout | null>(null)
  const layout = normalised(stored)
  const admin = isAdmin(account)
  const list = done ?? []
  const hr = list.map(handicapRound).filter((h): h is NonNullable<typeof h> => !!h)
  const rt = model ? rating(model, hr) : null
  const pbs = personalBestIds(list)
  const top = bests(list).slice(0, 6)
  const recent = list.slice(0, 10)
  const year = new Date().getFullYear()
  const season = seasons(list).filter(s => s.year === year)
  const noLocation = list.filter(r => locationStatus(r) !== 'SET')

  if (editing) return <>
    <Bar title="Arrange the menu"><button onClick={async () => { await setKv('homeLayout', normalised(editing)); setEditing(null) }}>Done</button></Bar>
    <main class="narrow"><Arrange layout={editing} shown={layout.order.filter(id => id !== 'JOIN' && (id !== 'ADMIN' || admin))} onChange={setEditing} /></main>
  </>

  return (
    <>
      <Bar title={account ? `Welcome, ${account.username}` : 'Field Archery Scoring'} />
      <main>
        <div class="grid3">
          <Card title="Add a round" cls="hi">
            <div class="sub" style="color:inherit">Type in a round shot on paper (or scored before): the details, then each target's points.</div>
            <button class="filled" style="align-self:flex-start" onClick={() => go('/add')}>Add a round</button>
          </Card>
          <Card title="Handicap rating">
            {!model ? <div class="sub">The handicap downloads from the server the next time there's signal.</div>
              : rt ? <><div class="big">{rt.rating}</div><div class="sub">Average of your last {rt.rounds} Big Game rounds, out of 1000</div></>
              : <div class="sub">Add 3 Big Game rounds for a rating.</div>}
            <button class="outlined" style="align-self:flex-start" onClick={() => go('/stats')}>Stats</button>
          </Card>
          <Card title={`${year} so far`}>
            {!season.length ? <div class="sub">No rounds this year yet.</div> : season.map(s => (
              <div class="row between"><span>{ROUND_TYPE_NAMES[s.type]}</span>
                <span class="sub">{s.rounds} round{s.rounds === 1 ? '' : 's'} · avg {s.stats.averagePerAnimal != null ? one(s.stats.averagePerAnimal) : '–'} · best {s.bestRound ? roundTotal(s.bestRound) : '–'}</span></div>
            ))}
            <div class="sub">{list.length} finished round{list.length === 1 ? '' : 's'} in all</div>
          </Card>
        </div>

        {(offers > 0 && account) || (duplicates > 0 && admin) || (account && noLocation.length > 0) || going.length > 0 ? <div class="grid2">
          {offers > 0 && account && <button class="tile hi" onClick={() => go('/friends')}><span class="dot">☺</span>
            <span class="grow"><b>{offers} round{offers === 1 ? '' : 's'} scored for you</b><span class="sub">Accept or decline them in Friends</span></span></button>}
          {duplicates > 0 && admin && <button class="tile warn" onClick={() => go('/admin/duplicates')}><span class="dot">⚠</span>
            <span class="grow"><b>{duplicates} possible duplicate location{duplicates === 1 ? '' : 's'}</b><span class="sub">Merge or mark as different places</span></span></button>}
          {account && noLocation.length > 0 && <button class="tile" onClick={() => go(`/card/${noLocation[0].id}`)}><span class="dot">📍</span>
            <span class="grow"><b>{noLocation.length} round{noLocation.length === 1 ? '' : 's'} without a shoot location</b>
              <span class="sub">Latest: {ukDate(noLocation[0].shootDate)} · {targetsLine(noLocation[0])}. Open it and tap the location line.</span></span></button>}
          {going.map(r => (
            <button class="tile" onClick={() => go(`/grid/${r.id}`)}><span class="dot">✎</span>
              <span class="grow"><b>Unfinished: {ROUND_TYPE_NAMES[r.type]}{r.groupId ? ' (group)' : ''}</b>
                <span class="sub">{ukDate(r.shootDate)} · {targetsComplete(r)} of {r.targetCount} targets · {roundTotal(r)} points · finish typing it in</span></span></button>
          ))}
        </div> : null}

        <div class="grid2 top">
          <Card title="Recent rounds">
            {!recent.length ? <div class="sub">No finished rounds yet. Add one from a paper scorecard.</div> : <table class="card-table click">
              <thead><tr><th>Date</th><th>Round</th><th>Location</th>{model && <th class="num">Handicap</th>}<th class="num">Total</th></tr></thead>
              <tbody>{recent.map(r => {
                const h = model ? handicapRound(r) : null
                return (
                  <tr onClick={() => go(`/card/${r.id}`)}>
                    <td>{ukDate(r.shootDate)}</td>
                    <td>{r.type === 'WOODSMAN' ? 'Woodsman · ' : ''}{targetsLine(r)}<div class="sub">{classLine(r)}</div></td>
                    <td>{r.locationName ?? <span class="sub">–</span>}</td>
                    {model && <td class="num">{h ? roundPoints(model, h) : '–'}</td>}
                    <td class="num"><b>{roundTotal(r)}</b> {pbs.has(r.id) && <span class="pb">PB</span>}</td>
                  </tr>
                )
              })}</tbody>
            </table>}
            {list.length > recent.length && <button style="align-self:flex-start" onClick={() => go('/history')}>All {list.length} rounds ›</button>}
          </Card>
          <Card title="Personal bests">
            {!top.length ? <div class="sub">Fully scored rounds show here.</div> : <table class="card-table click">
              <thead><tr><th>Round</th><th>Bow</th><th class="num">Shot</th><th class="num">Avg</th><th class="num">Best</th></tr></thead>
              <tbody>{top.map(b => (
                <tr onClick={() => go(`/card/${b.best.id}`)}>
                  <td>{ROUND_TYPE_NAMES[b.type]} · {b.targetCount} targets<div class="sub">{ukDate(b.best.shootDate)}</div></td>
                  <td>{bowName(b.bowStyle)}</td><td class="num">{b.roundsShot}</td><td class="num">{one(b.averageTotal)}</td><td class="num"><b>{roundTotal(b.best)}</b></td>
                </tr>
              ))}</tbody>
            </table>}
            <button style="align-self:flex-start" onClick={() => go('/stats')}>Trends and seasons ›</button>
          </Card>
        </div>
        <button style="align-self:flex-start" onClick={() => setEditing(layout)}>⇅ Arrange the menu</button>
      </main>
    </>
  )
}

/** Live groups need phones (scoring in the woods); on a computer, say so. */
export function PhoneOnly() {
  return <>
    <Bar title="Live groups" />
    <main class="form">
      <Card title="Live groups are on phones" sub="Scoring a live group happens in the woods on the Android app or the web app on a phone. On a computer you can add rounds from paper, and see and edit your rounds once they're finished.">
        <button class="filled" style="align-self:flex-start" onClick={() => go('/add')}>Add a round</button>
      </Card>
    </main>
  </>
}
