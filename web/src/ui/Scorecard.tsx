import { useState } from 'preact/hooks'
import { Bar, Card, Dialog, go, replace, ukDate, useData } from './common'
import { getRound } from '../data/db'
import { deleteRound, groupOf, setStatus } from '../data/rounds'
import { ROUND_TYPE_NAMES, classLine, roundStats, roundTotal, target, targetsLine } from '../domain/round'
import { TARGET_TYPE_NAMES, describeAnimal, describeArrows, isComplete, totalPoints } from '../domain/scoring'

/** A round's scorecard: every target, the stats, and (in a group) everyone's totals. */
export function Scorecard({ id }: { id: string }) {
  const round = useData(() => getRound(id), [id])
  const group = useData(async () => { const r = await getRound(id); return r ? groupOf(r) : [] }, [id]) ?? []
  const [confirmDelete, setConfirmDelete] = useState(false)
  if (!round) return <Bar title="Scorecard" onBack={() => go('/')} />
  const s = roundStats(round)
  const name = round.groupRole && round.groupRole !== 'SCORER' ? round.groupName : null
  return (
    <>
      <Bar title={name ? `${name}'s card` : 'Scorecard'} onBack={() => go('/')} />
      <main>
        <Card cls="hi">
          <div class="row between"><div><div class="big">{roundTotal(round)}</div><div>{ROUND_TYPE_NAMES[round.type]} · {targetsLine(round)}</div></div>
            <div style="text-align:right"><div>{ukDate(round.shootDate)}</div><div>{classLine(round)}</div>{round.locationName && <div>{round.locationName}</div>}</div></div>
        </Card>
        <Card>
          <div class="stats">
            <div><b>{s.averagePerAnimal != null ? s.averagePerAnimal.toFixed(1) : '–'}</b><span>avg per {round.type === 'WOODSMAN' ? 'target' : 'animal'} (of {s.maxPerAnimal})</span></div>
            <div><b>{s.targetsScored}/{round.targetCount}</b><span>targets scored</span></div>
            <div><b>{round.type === 'WOODSMAN' ? s.kills : s.innerKills}</b><span>{round.type === 'WOODSMAN' ? 'kills' : 'inner kills'}</span></div>
            <div><b>{s.byArrow.join(' / ')}</b><span>{round.type === 'WOODSMAN' ? 'kills on arrow 1 / 2 / 3' : 'hits on arrow 1 / 2 / 3'}</span></div>
            <div><b>{round.type === 'WOODSMAN' ? s.wounds : s.kills}</b><span>{round.type === 'WOODSMAN' ? 'wounds' : 'kills'}</span></div>
            <div><b>{s.misses}</b><span>misses</span></div>
          </div>
        </Card>
        {group.length > 1 && (
          <Card title="The group">
            {group.map(m => (
              <button class="row between" style="padding:6px 0;color:inherit" onClick={() => go(`/card/${m.id}`)}>
                <span>{m.groupRole === 'SCORER' ? 'You' : m.groupName} <span class="sub">{classLine(m)}</span></span><b>{roundTotal(m)}</b>
              </button>
            ))}
          </Card>
        )}
        <Card>
          <table class="card-table">
            <thead><tr><th>#</th><th>Target</th><th class="num">Points</th></tr></thead>
            <tbody>
              {Array.from({ length: round.targetCount }, (_, i) => target(round, i + 1)).map(t => (
                <tr onClick={() => round.groupRole !== 'MIRROR' && go(`/score/${group[0]?.id ?? round.id}?t=${t.targetNo}`)}>
                  <td>{t.targetNo}</td>
                  <td><span class="sub">{t.type !== 'STANDARD' && t.type !== 'WOODSMAN' ? `${TARGET_TYPE_NAMES[t.type]}: ` : ''}</span>
                    {!isComplete(t) && totalPoints(t) === 0 ? <span class="sub">Not scored</span>
                      : t.overridePoints != null ? 'Entered by hand'
                      : t.type === 'WOODSMAN' ? describeArrows(t.arrows) : t.animals.map(describeAnimal).join(' · ')}</td>
                  <td class="num"><b>{totalPoints(t)}</b></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        {round.groupRole !== 'MIRROR' && <div class="row">
          {round.status === 'COMPLETED'
            ? <button class="tonal" onClick={async () => { await setStatus(round, 'IN_PROGRESS'); go(`/score/${group[0]?.id ?? round.id}`) }}>Reopen to edit</button>
            : <button class="filled" onClick={() => go(`/score/${group[0]?.id ?? round.id}`)}>Carry on scoring</button>}
          <button class="danger" onClick={() => setConfirmDelete(true)}>Delete</button>
        </div>}
      </main>
      <Dialog open={confirmDelete} title="Delete this round?" onClose={() => setConfirmDelete(false)}
        actions={<><button onClick={() => setConfirmDelete(false)}>Keep</button>
          <button class="danger" onClick={async () => { setConfirmDelete(false); await deleteRound(round); replace('/') }}>Delete</button></>}>
        {group.length > 1 ? "Everyone's round in this group is deleted." : 'It\'s deleted here and, once synced, from the server.'}
      </Dialog>
    </>
  )
}
