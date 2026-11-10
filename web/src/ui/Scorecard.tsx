import { useState } from 'preact/hooks'
import { Bar, Card, Dialog, Menu, go, replace, ukDate, useData, useDesktop } from './common'
import { CardHeader, GroupCard, RunningStats, TargetRow, memberName, roleLabel } from './Parts'
import { getRound } from '../data/db'
import { getAccount } from '../data/account'
import { LocationChip, LocationSheet } from './Locations'
import { deleteRound, groupOf, setStatus } from '../data/rounds'
import { ROUND_TYPE_NAMES, canGoTo, classLine, roundStats, roundTotal, target, targetsLine } from '../domain/round'
import { TARGET_TYPE_NAMES, describeAnimal, describeArrows, isComplete, totalPoints } from '../domain/scoring'
import { finished } from '../data/rounds'
import { getModel } from '../data/handicap'
import { personalBestIds, previousBest } from '../domain/stats'
import { handicapRound, roundPoints } from '../domain/handicap'
import { MoreStats } from './Stats'
import { ExportDialog } from './Export'
import { bowName, type Round, type RoundStats } from '../domain/round'
import type { HandicapModel } from '../domain/handicap'

/** A round's scorecard (as the app's): header, PB, location, the group, stats, handicap, and every target. */
export function Scorecard({ id }: { id: string }) {
  const round = useData(() => getRound(id), [id])
  const group = useData(async () => { const r = await getRound(id); return r ? groupOf(r) : [] }, [id]) ?? []
  const mine = useData(finished) ?? []
  const model = useData(getModel)
  const account = useData(getAccount)
  const desktop = useDesktop()
  const [locOpen, setLocOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [exporting, setExporting] = useState(false)
  if (!round) return <Bar title="Scorecard" onBack={() => go('/')} />
  const s = roundStats(round)
  const own = round.groupRole == null || round.groupRole === 'SCORER'
  const beat = own && round.status === 'COMPLETED' && personalBestIds(mine).has(round.id) ? previousBest(round, mine) : undefined
  const h = round.status === 'COMPLETED' ? handicapRound(round) : null
  const inGroup = group.length > 1
  const mirror = round.groupRole === 'MIRROR'
  const editTarget = (n: number) => go(desktop ? `/grid/${round.id}` : `/score/${round.id}${n ? `?t=${n}` : ''}`)
  return (
    <>
      <Bar title="Scorecard" sub={inGroup ? (own ? `You · group of ${group.length}` : `${memberName(round)} · ${roleLabel(round)}`) : undefined} onBack={() => go('/')}>
        {!mirror && <button class="icon" aria-label="Edit scores" onClick={() => editTarget(0)}>✎</button>}
        {!mirror && <Menu items={[
          round.status === 'COMPLETED' && { text: inGroup ? 'Reopen group round' : 'Reopen round', onClick: () => setStatus(round, 'IN_PROGRESS') },
          { text: 'Edit round details', onClick: () => go(`/edit/${round.id}`) },
          desktop && { text: 'Export…', onClick: () => setExporting(true) },
          { text: inGroup ? 'Delete group round' : 'Delete round', onClick: () => setConfirmDelete(true), danger: true },
        ]} />}
      </Bar>
      <main>
        <div class="split"><div>
        <CardHeader r={round} />
        {beat && <Card title="New personal best" cls="hi">
          <div>{roundTotal(round)} beats your previous best of {roundTotal(beat)} ({ukDate(beat.shootDate)}) for {round.targetCount}-target {ROUND_TYPE_NAMES[round.type]}, {bowName(round.bowStyle)}. +{roundTotal(round) - roundTotal(beat)} points.</div>
        </Card>}
        {!mirror && <LocationChip round={round} signedIn={!!account} onClick={() => setLocOpen(true)} />}
        {inGroup && <GroupCard group={group} currentId={round.id} />}
        <RunningStats s={s} targetCount={round.targetCount} noTotal />
        {model && h && <HandicapCard points={roundPoints(model, h)} />}
        <MoreStats s={s} />
        </div><div>
        <h3 class="section">Targets</h3>
        <div class="card low" style="padding:0;overflow:hidden">
          {Array.from({ length: round.targetCount }, (_, i) => target(round, i + 1)).map(t => (
            <TargetRow t={t} locked={!canGoTo(round, t.targetNo)} readOnly={mirror} onGoTo={mirror ? undefined : () => editTarget(t.targetNo)} goToLabel={`Edit target ${t.targetNo}`} />
          ))}
        </div>
        </div></div>
      </main>
      {!mirror && <button class="fab" onClick={() => editTarget(0)}>✎ Edit scores</button>}
      <LocationSheet roundId={round.id} open={locOpen} onClose={() => setLocOpen(false)} />
      <ExportDialog open={exporting} rounds={[round]} onClose={() => setExporting(false)}
        what={`This round: ${ukDate(round.shootDate)}, ${targetsLine(round)}, ${roundTotal(round)} points.`} />
      <Dialog open={confirmDelete} title={inGroup ? 'Delete group round?' : 'Delete round?'} onClose={() => setConfirmDelete(false)}
        actions={<><button onClick={() => setConfirmDelete(false)}>Cancel</button>
          <button class="danger" onClick={async () => { setConfirmDelete(false); await deleteRound(round); replace('/') }}>Delete</button></>}>
        {inGroup ? "This removes everyone's round in the group and all their scores. Requests already sent to friends are taken back." : 'This removes the round and all its scores.'}
      </Dialog>
    </>
  )
}

export function RoundHeader({ round }: { round: Round }) {
  return (
    <Card cls="hi">
      <div class="row between"><div><div class="big">{roundTotal(round)}</div><div>{ROUND_TYPE_NAMES[round.type]} · {targetsLine(round)}</div></div>
        <div style="text-align:right"><div>{ukDate(round.shootDate)}</div><div>{classLine(round)}</div>{round.locationName && <div>{round.locationName}</div>}
          {round.startTarget && <div>Started at target {round.startTarget}</div>}</div></div>
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
      <CardHeader r={round} showLocation />
      <RunningStats s={s} targetCount={round.targetCount} noTotal />
      {model && h && <HandicapCard points={roundPoints(model, h)} />}
      <MoreStats s={s} />
    </div><div>
      <h3 class="section">Targets</h3>
      <div class="card low" style="padding:0;overflow:hidden">
        {Array.from({ length: round.targetCount }, (_, i) => target(round, i + 1)).map(t => <TargetRow t={t} readOnly />)}
      </div>
    </div></div>
  )
}
