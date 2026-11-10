// Pieces shared by several screens, laid out as the Android app's (2.7.01): running stats, round cards in lists,
// the scorecard header and target rows, and the group card.
import { useState } from 'preact/hooks'
import { go } from './common'
import { classLine, roundTotal, targetsComplete, targetsLine, type Round, type RoundStats } from '../domain/round'
import { TARGET_TYPE_NAMES, animalLabel, animalPoints, describeAnimal, describeArrows, isComplete, ordinal, totalPoints, type TargetScore } from '../domain/scoring'

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
/** "Mon 9 November 2026", as the app's long date. */
export function longDate(iso: string): string {
  const d = new Date(`${iso.slice(0, 10)}T12:00:00`)
  return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`
}

/** "Woodsman · Gents Adult LB" (Big Game: just the class line). */
export const typeAndClass = (r: Round) => (r.type === 'WOODSMAN' ? 'Woodsman · ' : '') + classLine(r)

/** Total, average, progress, hit counts and which arrow scored. [compact] drops the arrow row (scoring screen). */
/** [noTotal] (2.7.06): on scorecards, whose header already shows the total in large type. */
export function RunningStats({ s, targetCount, compact, noTotal }: { s: RoundStats; targetCount: number; compact?: boolean; noTotal?: boolean }) {
  const big = s.maxPerAnimal === 24
  return (
    <div class="card hi runstats">
      <div class={`stats ${noTotal ? 'two' : ''}`}>
        {!noTotal && <div><b class="lg">{s.total}</b><span>Total</span></div>}
        <div><b class="lg">{s.averagePerAnimal != null ? s.averagePerAnimal.toFixed(1) : '–'}</b><span>{big ? 'Avg per animal' : 'Avg per target'}</span></div>
        <div><b class="lg">{s.targetsScored}/{targetCount}</b><span>Scored</span></div>
      </div>
      <hr />
      <div class={`stats ${big ? 'four' : ''}`}>
        {big && <div><b>{s.innerKills}</b><span>Inner kill</span></div>}
        <div><b>{s.kills}</b><span>Kill</span></div>
        <div><b>{s.wounds}</b><span>Wound</span></div>
        <div><b>{s.misses}</b><span>Miss</span></div>
      </div>
      {!compact && <div class="stats four">
        {s.byArrow.map((c, i) => <div><b>{c}</b><span>{big ? `${ordinal(i + 1)} arrow` : `Kill ${ordinal(i + 1)}`}</span></div>)}
        <div style={s.overrides ? '' : 'opacity:.5'}><b>{s.overrides}</b><span>Overrides</span></div>
      </div>}
    </div>
  )
}

/** A round in a list: date, class line, total; IN PROGRESS badge, PB, or whether it's backed up. */
/** [net] (2.9): the net score with the personal handicap before this round, and that handicap. */
export function RoundCard({ r, showSync, pb, groupSize = 1, net, onClick }: { r: Round; showSync?: boolean; pb?: boolean; groupSize?: number; net?: { net: number; handicap: number } | null; onClick: () => void }) {
  const going = r.status === 'IN_PROGRESS'
  return (
    <button class={`tile roundcard ${going ? 'going' : ''}`} onClick={onClick}>
      <span class="grow">
        {going && <span class="badge-going">IN PROGRESS</span>}
        <span class="row" style="gap:8px"><b>{longDate(r.shootDate)}</b>{pb && <span class="pb">PB</span>}</span>
        <span class="sub">{typeAndClass(r)} · {targetsLine(r)}{groupSize > 1 ? ` · group of ${groupSize}` : ''}</span>
        {r.locationName && <span class="sub">{r.locationName}</span>}
        {showSync && !going && <SyncLabel r={r} />}
      </span>
      <span style="text-align:right"><span class="num-big">{roundTotal(r)}</span><span class="sub" style="display:block">points</span>
        {net && <span class="sub" style="display:block">net {net.net} <span style="opacity:.8">(hcp {net.handicap})</span></span>}</span>
    </button>
  )
}

export function SyncLabel({ r }: { r: Round }) {
  return r.failed ? <span class="sub" style="color:var(--error)">⚠ Couldn't upload</span>
    : r.pending ? <span class="sub">↻ Waiting to upload</span>
    : <span class="sub" style="color:var(--primary)">✓ Backed up</span>
}

/** Scorecard header: FINISHED / IN PROGRESS, the date, round and class, total. */
export function CardHeader({ r, showLocation }: { r: Round; showLocation?: boolean }) {
  const done = r.status === 'COMPLETED'
  return (
    <div class="row between" style="flex-wrap:nowrap;align-items:center">
      <div class="col" style="gap:4px">
        <span class={`status ${done ? 'done' : ''}`}>{done ? 'FINISHED' : 'IN PROGRESS'}</span>
        <span style="font-size:22px">{longDate(r.shootDate)}</span>
        <span class="sub">{typeAndClass(r)} · {targetsLine(r)}</span>
        {r.startTarget && <span class="sub">Started at target {r.startTarget}</span>}
        {showLocation && r.locationName && <span class="sub">{r.locationName}</span>}
      </div>
      <div style="text-align:right"><div class="huge">{roundTotal(r)}</div><div class="sub">points</div></div>
    </div>
  )
}

/** One target on a scorecard. Predator/prey and fans open to show each animal, with a button to go to the target. */
export function TargetRow({ t, current, locked, onGoTo, goToLabel, readOnly }: {
  t: TargetScore; current?: boolean; locked?: boolean; onGoTo?: () => void; goToLabel?: string; readOnly?: boolean
}) {
  const [open, setOpen] = useState(false)
  const multi = t.animals.length > 1
  const over = t.overridePoints != null
  const head = locked ? `Target ${t.targetNo}`
    : t.type === 'WOODSMAN' && !over && t.arrows ? describeArrows(t.arrows)
    : !isComplete(t) ? 'Not scored'
    : over ? 'Overridden'
    : t.type === 'STANDARD' ? describeAnimal(t.animals[0])
    : TARGET_TYPE_NAMES[t.type] + (t.type === 'FAN' ? ` ×${t.animals.length}` : '')
  const clickable = !locked && (multi || (!readOnly && !!onGoTo))
  return (
    <div class={`trow ${current ? 'current' : ''} ${locked ? 'locked' : ''}`}>
      <button class="trow-main" disabled={!clickable} onClick={() => (multi ? setOpen(!open) : onGoTo?.())}>
        <span class="tnum">{locked ? '🔒' : t.targetNo}</span>
        <span class="grow"><span>{head}</span>{over && isComplete(t) && <span class="sub" style="display:block">Entered by hand</span>}</span>
        <b>{isComplete(t) || totalPoints(t) > 0 ? totalPoints(t) : '–'}</b>
        {multi && !locked && <span class="sub">{open ? '▲' : '▼'}</span>}
      </button>
      {multi && open && !locked && (
        <div class="trow-more">
          {t.animals.map((a, i) => <div class="row between"><span class="sub">{animalLabel(t, i)}: {describeAnimal(a)}</span><b>{animalPoints(a)}</b></div>)}
          {!readOnly && onGoTo && <button style="align-self:flex-start" onClick={onGoTo}>{goToLabel ?? `Go to target ${t.targetNo}`}</button>}
        </div>
      )}
    </div>
  )
}

const ROLE_LABEL: Record<string, string> = { SCORER: 'You', FRIEND: 'Friend', GUEST: 'Guest', MIRROR: 'Own phone' }
export const roleLabel = (r: Round) => ROLE_LABEL[r.groupRole ?? 'SCORER']
export const memberName = (r: Round) => (r.groupRole == null || r.groupRole === 'SCORER' ? 'You' : r.groupName ?? '?')

/** What happens to a friend's or guest's round. */
function MemberStatus({ m }: { m: Round }) {
  const role = m.groupRole
  if (!role || role === 'SCORER') return null
  const name = m.groupName ?? ''
  const finished = m.status === 'COMPLETED'
  const [text, colour] = role === 'GUEST' ? ['Guest · kept on this phone for 30 days', '']
    : role === 'MIRROR' ? ['Scoring on their own phone · their round is theirs', '']
    : !finished ? ['Friend · sent to them as a request when the round is finished', '']
    : m.failed ? ['⚠ Couldn\'t send (still friends?)', 'var(--error)']
    : m.pending ? [`↻ Waiting to send to ${name}`, ''] : [`✓ Sent to ${name} to accept`, 'var(--primary)']
  return <span class="sub" style={`display:block;font-size:12px;${colour ? `color:${colour}` : ''}`}>{text}</span>
}

/** Everyone in the group, highest total first, with what's happening to each round. */
export function GroupCard({ group, currentId }: { group: Round[]; currentId: string }) {
  return (
    <div class="card low">
      <h2>Group</h2>
      {[...group].sort((a, b) => roundTotal(b) - roundTotal(a)).map((m, i) => (
        <div class="row between" style="flex-wrap:nowrap">
          <span class="sub" style="width:20px">{i + 1}</span>
          <span class="grow"><span style={m.id === currentId ? 'font-weight:700' : ''}>{memberName(m)}</span>
            <span class="sub" style="display:block">{typeAndClass(m)} · {targetsComplete(m)}/{m.targetCount} targets</span>
            <MemberStatus m={m} /></span>
          {m.id !== currentId ? <button onClick={() => go(`/card/${m.id}`)}>Open</button> : <span />}
          <b style="font-size:20px">{roundTotal(m)}</b>
        </div>
      ))}
    </div>
  )
}

