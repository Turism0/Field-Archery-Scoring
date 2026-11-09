import { useState } from 'preact/hooks'
import { Bar, Card, Dialog, go, replace, ukDate, useData } from './common'
import { getRound } from '../data/db'
import { getAccount } from '../data/account'
import { LocationChip, LocationSheet } from './Locations'
import { deleteRound, groupOf, setStatus } from '../data/rounds'
import { ROUND_TYPE_NAMES, classLine, roundStats, roundTotal, target, targetsLine } from '../domain/round'
import { TARGET_TYPE_NAMES, describeAnimal, describeArrows, isComplete, totalPoints } from '../domain/scoring'
import { finished } from '../data/rounds'
import { getModel } from '../data/handicap'
import { personalBestIds, previousBest } from '../domain/stats'
import { handicapRound, roundPoints } from '../domain/handicap'
import { MoreStats } from './Stats'
import { bowName, type Round, type RoundStats } from '../domain/round'
import type { HandicapModel } from '../domain/handicap'

/** A round's scorecard: every target, the stats, and (in a group) everyone's totals. */
export function Scorecard({ id }: { id: string }) {
  const round = useData(() => getRound(id), [id])
  const group = useData(async () => { const r = await getRound(id); return r ? groupOf(r) : [] }, [id]) ?? []
  const mine = useData(finished) ?? []
  const model = useData(getModel)
  const account = useData(getAccount)
  const [locOpen, setLocOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  if (!round) return <Bar title="Scorecard" onBack={() => go('/')} />
  const s = roundStats(round)
  const own = round.groupRole == null || round.groupRole === 'SCORER'
  const beat = own && round.status === 'COMPLETED' && personalBestIds(mine).has(round.id) ? previousBest(round, mine) : undefined
  const h = round.status === 'COMPLETED' ? handicapRound(round) : null
  const name = round.groupRole && round.groupRole !== 'SCORER' ? round.groupName : null
  return (
    <>
      <Bar title={name ? `${name}'s card` : 'Scorecard'} onBack={() => go('/')} />
      <main>
        <div class="split"><div>
        <RoundHeader round={round} />
        {beat && <Card title="New personal best" cls="hi">
          <div>{roundTotal(round)} beats your previous best of {roundTotal(beat)} ({ukDate(beat.shootDate)}) for {round.targetCount}-target {ROUND_TYPE_NAMES[round.type]}, {bowName(round.bowStyle)}. +{roundTotal(round) - roundTotal(beat)} points.</div>
        </Card>}
        {round.groupRole !== 'MIRROR' && <LocationChip round={round} signedIn={!!account} onClick={() => setLocOpen(true)} />}
        <StatsCard round={round} s={s} />
        {model && h && <HandicapCard points={roundPoints(model, h)} />}
        <MoreStats s={s} />
        {group.length > 1 && (
          <Card title="The group">
            {group.map(m => (
              <button class="row between" style="padding:6px 0;color:inherit" onClick={() => go(`/card/${m.id}`)}>
                <span>{m.groupRole === 'SCORER' ? 'You' : m.groupName} <span class="sub">{classLine(m)}</span></span><b>{roundTotal(m)}</b>
              </button>
            ))}
          </Card>
        )}
        {round.groupRole !== 'MIRROR' && <div class="row">
          {round.status === 'COMPLETED'
            ? <button class="tonal" onClick={async () => { await setStatus(round, 'IN_PROGRESS'); go(`/score/${group[0]?.id ?? round.id}`) }}>Reopen to edit</button>
            : <button class="filled" onClick={() => go(`/score/${group[0]?.id ?? round.id}`)}>Carry on scoring</button>}
          <button class="outlined" onClick={() => go(`/edit/${round.id}`)}>Edit round details</button>
          <button class="danger" onClick={() => setConfirmDelete(true)}>Delete</button>
        </div>}
        </div><div>
        <TargetTable round={round} onTarget={round.groupRole !== 'MIRROR' ? n => go(`/score/${group[0]?.id ?? round.id}?t=${n}`) : undefined} />
        </div></div>
      </main>
      <LocationSheet roundId={round.id} open={locOpen} onClose={() => setLocOpen(false)} />
      <Dialog open={confirmDelete} title="Delete this round?" onClose={() => setConfirmDelete(false)}
        actions={<><button onClick={() => setConfirmDelete(false)}>Keep</button>
          <button class="danger" onClick={async () => { setConfirmDelete(false); await deleteRound(round); replace('/') }}>Delete</button></>}>
        {group.length > 1 ? "Everyone's round in this group is deleted." : 'It\'s deleted here and, once synced, from the server.'}
      </Dialog>
    </>
  )
}

export function RoundHeader({ round }: { round: Round }) {
  return (
    <Card cls="hi">
      <div class="row between"><div><div class="big">{roundTotal(round)}</div><div>{ROUND_TYPE_NAMES[round.type]} · {targetsLine(round)}</div></div>
        <div style="text-align:right"><div>{ukDate(round.shootDate)}</div><div>{classLine(round)}</div>{round.locationName && <div>{round.locationName}</div>}</div></div>
    </Card>
  )
}

export function StatsCard({ round, s }: { round: Round; s: RoundStats }) {
  const w = round.type === 'WOODSMAN'
  return (
    <Card>
      <div class="stats">
        <div><b>{s.averagePerAnimal != null ? s.averagePerAnimal.toFixed(1) : '–'}</b><span>avg per {w ? 'target' : 'animal'} (of {s.maxPerAnimal})</span></div>
        <div><b>{s.targetsScored}/{round.targetCount}</b><span>targets scored</span></div>
        <div><b>{w ? s.kills : s.innerKills}</b><span>{w ? 'kills' : 'inner kills'}</span></div>
        <div><b>{s.byArrow.join(' / ')}</b><span>{w ? 'kills on arrow 1 / 2 / 3' : 'hits on arrow 1 / 2 / 3'}</span></div>
        <div><b>{w ? s.wounds : s.kills}</b><span>{w ? 'wounds' : 'kills'}</span></div>
        <div><b>{s.misses}</b><span>misses</span></div>
      </div>
    </Card>
  )
}

export function HandicapCard({ points }: { points: number }) {
  return (
    <Card>
      <div class="row between" style="flex-wrap:nowrap"><div><h2>Handicap</h2><div class="sub">Out of 1000: the same for the same performance with any bow or class</div></div>
        <span class="num-big">{points}</span></div>
    </Card>
  )
}

export function TargetTable({ round, onTarget }: { round: Round; onTarget?: (n: number) => void }) {
  return (
    <Card>
      <table class="card-table">
        <thead><tr><th>#</th><th>Target</th><th class="num">Points</th></tr></thead>
        <tbody>
          {Array.from({ length: round.targetCount }, (_, i) => target(round, i + 1)).map(t => (
            <tr onClick={() => onTarget?.(t.targetNo)}>
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
  )
}

/** A round from the server (a friend's, or one scored for you) to look at: no editing. */
export function ReadOnlySheet({ round, model }: { round: Round; model: HandicapModel | null | undefined }) {
  const s = roundStats(round)
  const h = round.status === 'COMPLETED' ? handicapRound(round) : null
  return (
    <div class="split"><div>
      <RoundHeader round={round} />
      <StatsCard round={round} s={s} />
      {model && h && <HandicapCard points={roundPoints(model, h)} />}
      <MoreStats s={s} />
    </div><div>
      <TargetTable round={round} />
    </div></div>
  )
}
