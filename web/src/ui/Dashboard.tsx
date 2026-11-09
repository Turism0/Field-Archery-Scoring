import { useState } from 'preact/hooks'
import { Bar, Card, go, one, ukDate, useData } from './common'
import { getAccount, isAdmin } from '../data/account'
import { finished, inProgress } from '../data/rounds'
import { getKv, setKv } from '../data/db'
import { getModel } from '../data/handicap'
import { ROUND_TYPE_NAMES, bowName, classLine, roundTotal, targetsComplete, targetsLine } from '../domain/round'
import { handicapRound, rating, roundPoints } from '../domain/handicap'
import { bests, personalBestIds } from '../domain/stats'
import { normalised, type HomeLayout } from '../domain/homeLayout'
import { Arrange } from './Home'

/** Home on a wide screen: a dashboard (the sidebar does the tiles' job). */
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
  const hr = (done ?? []).map(handicapRound).filter((h): h is NonNullable<typeof h> => !!h)
  const rt = model ? rating(model, hr) : null
  const pbs = personalBestIds(done ?? [])
  const top = bests(done ?? []).slice(0, 6)
  const recent = (done ?? []).slice(0, 8)

  if (editing) return <>
    <Bar title="Arrange the menu"><button onClick={async () => { await setKv('homeLayout', normalised(editing)); setEditing(null) }}>Done</button></Bar>
    <main class="narrow"><Arrange layout={editing} shown={layout.order.filter(id => id !== 'ADMIN' || admin)} onChange={setEditing} /></main>
  </>

  return (
    <>
      <Bar title={account ? `Welcome, ${account.username}` : 'Field Archery Scoring'} />
      <main>
        <div class="grid3">
          <Card title="Score a round" cls="hi">
            <div class="sub" style="color:inherit">Big Game or Woodsman, on your own, a group on this computer, or a live group.</div>
            <div class="row"><button class="filled" onClick={() => go('/new')}>New round</button><button class="outlined" onClick={() => go('/join')}>Join a live group</button></div>
          </Card>
          <Card title="Handicap rating">
            {!model ? <div class="sub">The handicap downloads from the server the next time there's signal.</div>
              : rt ? <><div class="big">{rt.rating}</div><div class="sub">Average of your last {rt.rounds} Big Game rounds, out of 1000</div></>
              : <div class="sub">Finish 3 Big Game rounds for a rating.</div>}
            <button class="outlined" style="align-self:flex-start" onClick={() => go('/stats')}>Stats</button>
          </Card>
          <Card title="Your rounds">
            <div class="big">{done?.length ?? '–'}</div><div class="sub">finished{going.length ? ` · ${going.length} in progress` : ''}</div>
            <button class="outlined" style="align-self:flex-start" onClick={() => go('/history')}>Score history</button>
          </Card>
        </div>

        {(offers > 0 && account) || (duplicates > 0 && admin) ? <div class="grid2">
          {offers > 0 && account && <button class="tile hi" onClick={() => go('/friends')}><span class="dot">☺</span>
            <span class="grow"><b>{offers} round{offers === 1 ? '' : 's'} scored for you</b><span class="sub">Accept or decline them in Friends</span></span></button>}
          {duplicates > 0 && admin && <button class="tile warn" onClick={() => go('/admin/duplicates')}><span class="dot">⚠</span>
            <span class="grow"><b>{duplicates} possible duplicate location{duplicates === 1 ? '' : 's'}</b><span class="sub">Merge or mark as different places</span></span></button>}
        </div> : null}

        {going.length > 0 && <>
          <h3 class="section">In progress</h3>
          <div class="grid2">{going.map(r => (
            <button class="tile" onClick={() => go(`/score/${r.id}`)}><span class="dot">🎯</span>
              <span class="grow"><b>Continue: {ROUND_TYPE_NAMES[r.type]}{r.groupId ? ' (group)' : ''}</b>
                <span class="sub">{ukDate(r.shootDate)} · {classLine(r)} · {targetsComplete(r)} of {r.targetCount} targets · {roundTotal(r)} points</span></span></button>
          ))}</div>
        </>}

        <div class="grid2 top">
          <Card title="Recent rounds">
            {!recent.length ? <div class="sub">No finished rounds yet.</div> : <table class="card-table click">
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
            {(done?.length ?? 0) > recent.length && <button style="align-self:flex-start" onClick={() => go('/history')}>All {done!.length} rounds ›</button>}
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
          </Card>
        </div>
        <button style="align-self:flex-start" onClick={() => setEditing(layout)}>⇅ Arrange the menu</button>
      </main>
    </>
  )
}
