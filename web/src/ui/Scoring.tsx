import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import { Bar, Dialog, Menu, back, errorText, go, replace, toast, ukDate, useData } from './common'
import { getRound } from '../data/db'
import { getAccount } from '../data/account'
import { LocationChip, LocationSheet } from './Locations'
import { changedSince, deleteRound, groupOf, restore, saveTarget, setStatus, setTypeForGroup, snapshot, syncNow } from '../data/rounds'
import * as live from '../data/live'
import { canGoTo, firstUnscored, locationStatus, roundStats, roundTotal, target, targetsComplete, type Round } from '../domain/round'
import {
  FUN_MAX, HIT_NAMES, MAX_FAN, MIN_FAN, TARGET_TYPE_NAMES, WOODSMAN_MAX, animalLabel, animalPoints, arrowsAvailable, bigGamePoints, calculatedPoints,
  cleared, isComplete, ordinal, sameResult, totalPoints, withAnimalResult, withArrow, withOverride, withType, withoutLastArrow,
  woodsmanFinished, type AnimalResult, type Hit, type TargetScore, type TargetType,
} from '../domain/scoring'
import { RunningStats, TargetRow, memberName, typeAndClass } from './Parts'
import { useLiveCheck } from './LiveCheck'

/**
 * Scoring a round target by target; in a group, archer by archer on each target. Laid out as the Android app's
 * scoring screen (2.7.01): running stats, the target with its points tiles, Prev / Next at the bottom.
 */
export function Scoring({ id, target: startTarget }: { id: string; target?: number }) {
  const [current, setCurrent] = useState(id)
  const round = useData(() => getRound(current), [current])
  const group = useData(async () => { const r = await getRound(id); return r ? groupOf(r) : [] }, [id]) ?? []
  const [n, setN] = useState(startTarget ?? 0)
  const [overrideOpen, setOverrideOpen] = useState(false)
  const [overrideText, setOverrideText] = useState('')
  const [ask, setAsk] = useState<'finish' | 'delete' | 'takeover' | 'card' | null>(null)
  const account = useData(getAccount)
  const [locOpen, setLocOpen] = useState(false)
  const [askedName, setAskedName] = useState(false)

  // ---- editing a finished round (3.2.07, Pete): Save keeps the changes, Cancel puts the scores back ----
  // The scores as they were when the screen opened (null: the round wasn't finished, so this isn't editing).
  const [before, setBefore] = useState<Round[] | null | undefined>(undefined)
  useEffect(() => {
    if (before !== undefined || !round) return
    if (round.status === 'COMPLETED') snapshot(round).then(setBefore); else setBefore(null)
  }, [round?.id])
  const editing = !!before && round?.status === 'COMPLETED'
  const [leaving, setLeaving] = useState(false)
  const exiting = useRef(false)   // Save / Cancel are going back: that Back isn't asked about
  // The browser's or phone's Back while editing asks first: a spare history entry (marked editGuard) catches it.
  useEffect(() => {
    if (!editing) return
    if (!history.state?.editGuard) history.pushState({ editGuard: true }, '', location.href)
    const onPop = async () => {
      if (exiting.current) return
      if (before && await changedSince(before)) { history.pushState({ editGuard: true }, '', location.href); setLeaving(true) }
      else back()
    }
    addEventListener('popstate', onPop)
    return () => removeEventListener('popstate', onPop)
  }, [editing])
  /** Back to the screen before (past the spare history entry). */
  const leave = () => { exiting.current = true; if (history.state?.editGuard) history.go(-2); else back() }
  async function saveEdits() { setLeaving(false); syncNow(); leave() }
  async function cancelEdits() { setLeaving(false); if (before) await restore(before); syncNow(); leave() }

  // ---- live group (through the server) ----
  const gid = group[0]?.groupId
  const info = useData(() => live.liveInfo(gid), [gid])
  const [state, setState] = useState<live.LiveState | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const hasInfo = !!info
  const mine = useData(() => (gid && hasInfo ? live.myEntries(gid) : Promise.resolve({} as Record<string, TargetScore>)), [gid, hasInfo]) ?? {}
  useEffect(() => {
    if (!gid || !hasInfo) return
    let alive = true
    const tick = async () => {
      try { const s = await live.refresh(gid); if (alive) { setState(s); setProblem(null) } }
      catch (e) {
        // No signal (2.8): carry on from the copy kept here, with this phone's entries since.
        const off = await live.offlineState(gid).catch(() => null)
        if (alive) { if (off) setState(off); setProblem(errorText(e)) }
      }
    }
    tick()
    const t = setInterval(tick, 3000)
    return () => { alive = false; clearInterval(t) }
  }, [gid, hasInfo])


  const own = group.find(m => m.groupRole == null || m.groupRole === 'SCORER') ?? round
  // Nothing known nearby: ask for the location's name once (the location line works any time).
  useEffect(() => {
    if (own && locationStatus(own) === 'NEEDS_NAME' && !askedName && account) { setAskedName(true); setLocOpen(true) }
  }, [own?.locationState, !!account])

  const isLive = !!info
  const s = state
  // Double / 3+ scorers with no signal (2.8): checks by hand, read out or by QR.
  const liveCheck = useLiveCheck(s, gid ?? undefined, () => { if (gid) live.offlineState(gid).then(x => x && setState(x)) })
  const checked = !!s && live.checked(s)
  const canScore = !isLive || (!!s && live.canScore(s, current))
  // Double / 3+ scorers: what this device entered, shown until both scorers match.
  const shown = useMemo<Round | undefined>(() => {
    if (!round) return round
    if (!checked || !canScore) return round
    const overlay = Object.entries(mine).filter(([k]) => k.endsWith(`|${round.id}`)).map(([, t]) => t)
    if (!overlay.length) return round
    const byNo = new Map(round.targets.map(t => [t.targetNo, t]))
    overlay.forEach(t => byNo.set(t.targetNo, t))
    return { ...round, targets: [...byNo.values()].sort((a, b) => a.targetNo - b.targetNo) }
  }, [round, mine, checked, canScore])

  // The target on screen must be one you can go to. Double / 3+ scorers (2.8 fix): your own entries count, not just
  // saved (matched) scores, or moving to the next archer sent you back to target 1 until the other scorer caught up.
  useEffect(() => { if (shown && (n === 0 || n > shown.targetCount || !canGoTo(shown, n))) setN(firstUnscored(shown) ?? 1) }, [shown?.id, shown?.targetCount, !!s])

  if (!round || !shown || n === 0) return <Bar title="Round" onBack={() => go('/')} />
  const t = target(shown, n)
  const inGroup = group.length > 1
  const me = group.find(m => m.id === round.id)
  const name = me ? memberName(me) : 'You'
  const what = inGroup ? 'group round' : 'round'
  const whyNot = !isLive ? null : !s ? 'Connecting to the group…' : s.status === 'CLOSED' ? 'The group is closed.'
    : s.status !== 'STARTED' ? (s.offline ? 'Waiting for the host to start. This phone needs signal to hear that the group has started.' : 'Waiting for the host to start.') : canScore ? null
    : (() => { const who = live.scorerNames(s, current); return who.length ? `${who.join(' and ')} ${who.length > 1 ? 'score' : 'scores'} this card.` : 'Someone else scores this card.' })()
  const check = s ? live.checkText(s, n, current, canScore && !!mine[`${n}|${current}`]) : null
  const mismatch = !!s && live.cell(s, n, current) === 'MISMATCH'
  const youScore = s && s.status === 'STARTED' && s.method === 'MULTI'
    ? s.members.filter(m => live.canScore(s, m.memberId)).map(m => m.name) : []

  async function edit(change: (t: TargetScore) => TargetScore) {
    if (!canScore) { toast(whyNot ?? "You can't score this card."); return }
    let updated: TargetScore
    try { updated = change(t) } catch (e) { toast(errorText(e)); return }
    if (!checked) await saveTarget(current, updated)
    if (isLive && gid) await live.send(gid, current, updated)
  }

  async function setType(type: TargetType, fan = MIN_FAN) {
    await edit(x => withType(x, type, fan))
    // One phone scoring everyone: the others on this target get the same type.
    if (inGroup && (!isLive || (s && !checked && s.members.every(m => live.canScore(s, m.memberId))))) {
      await setTypeForGroup(round!, withType(t, type, fan))
    }
  }

  const toggle = (i: number, a: AnimalResult) => edit(x => withAnimalResult(x, i, sameResult(x.animals[i], a) ? { state: 'PENDING' } : a))

  /** Group: the next archer on this target, then the first archer on the next target (and back the other way). */
  function neighbour(by: 1 | -1): [Round, number] | null {
    const i = group.findIndex(m => m.id === current)
    let j = i + by, nn = n
    if (j >= group.length) { j = 0; nn = n + 1 } else if (j < 0) { j = group.length - 1; nn = n - 1 }
    return group[j] ? [group[j], nn] : null
  }
  const next = inGroup ? neighbour(1) : null
  const prev = inGroup ? neighbour(-1) : null
  const scored = isComplete(t)
  const last = inGroup ? !next || next[1] > round.targetCount : n >= round.targetCount
  const prevOk = inGroup ? !!prev && prev[1] >= 1 : n > 1
  const finished = round.status === 'COMPLETED'
  function step(by: 1 | -1) {
    if (inGroup) {
      const to = by === 1 ? next : prev
      if (!to || to[1] < 1 || to[1] > round!.targetCount) return
      setCurrent(to[0].id); setN(to[1])
    } else {
      const nn = n + by
      if (canGoTo(shown!, nn)) setN(nn)
    }
  }
  const nextLabel = !scored ? `Score target ${n} to continue`
    : last ? (finished ? (editing ? 'Save' : 'Done') : 'Finish round')
    : next ? (next[1] === n ? `Next: ${memberName(next[0])}` : `Target ${next[1]}: ${memberName(next[0])}`) : 'Next target'

  async function finish() {
    setAsk(null)
    if (isLive && gid && s && live.isHost(s)) { try { await live.close(gid) } catch (e) { toast(`Couldn't close the group (${errorText(e)}).`) } }
    await setStatus(round!, 'COMPLETED')
    syncNow()   // backed up straight away if there's signal
    replace(`/card/${round!.id}`)
  }

  const unscored = round.targetCount - targetsComplete(round)
  const left = group.reduce((x, m) => x + m.targetCount - targetsComplete(m), 0)
  const friends = group.filter(m => m.groupRole === 'FRIEND').length

  return (
    // 2.9.03 (Pete): the page is the phone's height; the target area scrolls only if it really can't fit.
    <div class="scoring">
      <Bar title={inGroup ? `Target ${n} · ${name}` : `Target ${n} of ${round.targetCount}`}
        sub={(inGroup ? `of ${round.targetCount} · ` : '') + `${typeAndClass(round)} · ${ukDate(round.shootDate)}`} onBack={() => go('/')}
        start={editing ? <button style="padding:8px 12px" onClick={cancelEdits}>Cancel</button> : undefined}>
        {editing && <button style="padding:8px 12px;font-weight:600" onClick={saveEdits}>Save</button>}
        <button class="icon" aria-label="Scorecard" onClick={() => setAsk('card')}>☰</button>
        <Menu items={[
          finished ? { text: `Reopen ${what}`, onClick: () => setStatus(round, 'IN_PROGRESS') } : { text: `Finish ${what}`, onClick: () => setAsk('finish') },
          { text: inGroup ? `Round details – ${name}` : 'Round details', onClick: () => go(`/edit/${current}`) },
          !!s && live.canTakeOver(s) && { text: 'Take over as host', onClick: () => setAsk('takeover') },
          ...liveCheck.menu,
          { text: `Delete ${what}`, onClick: () => setAsk('delete'), danger: true },
        ]} />
      </Bar>
      <div class="progress"><div style={`width:${(targetsComplete(round) / round.targetCount) * 100}%`} /></div>
      {own && <LocationChip round={own} signedIn={!!account} onClick={() => setLocOpen(true)} />}
      {inGroup && (
        <div class="strip" style="padding:8px 16px 0">
          {group.map(m => (
            <button class={`${m.id === current ? 'on' : ''} ${isComplete(target(m, n)) ? 'done' : ''}`}
              onClick={() => setCurrent(m.id)}>
              {memberName(m)}<br /><b>{roundTotal(m)}</b>
            </button>
          ))}
        </div>
      )}
      {isLive && (
        <div class={`banner ${problem || mismatch ? 'bad' : ''}`} style="margin:8px 16px 0">
          <b>Live group{s ? ` · ${s.code} · ${s.status === 'CLOSED' ? 'finished' : s.status === 'STARTED' ? live.methodOf(s.method)?.name : 'waiting to start'}` : ''}</b>
          {problem && <span>{s?.offline ? 'No signal. Keep scoring: scores are kept on this phone and sent when there\'s signal.' : `Can't reach the server (${problem}). Keep scoring: scores are sent when it's back.`}</span>}
          {whyNot && <span>{whyNot}</span>}
          {youScore.length > 0 && <span>You score: {youScore.join(', ')}</span>}
          {check && <span style={mismatch ? 'font-weight:600' : ''}>{check}</span>}
          {s && s.status === 'OPEN' && <button onClick={() => go(`/lobby/${s.groupId}`)}>{live.isHost(s) ? 'Show code and start' : 'Show the group'}</button>}
        </div>
      )}
      <main class="form">
        <RunningStats s={roundStats(shown)} targetCount={round.targetCount} compact />
        <div class={`col score-area ${t.animals.length > 1 ? 'multi' : ''}`} style={canScore ? '' : 'opacity:.55'}>
          {round.type === 'WOODSMAN'
            ? <WoodsmanEditor t={t} onArrow={a => edit(x => withArrow(x, a))} onUndo={() => edit(withoutLastArrow)}
                onOverride={() => { setOverrideText(String(totalPoints(t))); setOverrideOpen(true) }} onRemoveOverride={() => edit(x => withOverride(x, null))}
                onClear={() => edit(cleared)} />
            : <TargetEditor t={t} onType={setType} onResult={toggle}
                onOverride={() => { setOverrideText(String(totalPoints(t))); setOverrideOpen(true) }} onRemoveOverride={() => edit(x => withOverride(x, null))}
                onClear={() => edit(cleared)} onFun={p => edit(x => withOverride(x, p))} />}
        </div>
      </main>
      {own && <LocationSheet roundId={own.id} open={locOpen} onClose={() => setLocOpen(false)} />}

      <div class="nav">
        <button class="outlined" disabled={!prevOk} onClick={() => step(-1)}>‹ Prev</button>
        <button class="filled" disabled={!scored} onClick={() => (!last ? step(1) : finished ? (editing ? saveEdits() : go('/')) : setAsk('finish'))}>
          {nextLabel}{scored && !last ? ' ›' : ''}</button>
      </div>

      {liveCheck.dialogs}
      <Dialog open={leaving} title="Save your changes?" onClose={() => setLeaving(false)}
        actions={<><button onClick={() => setLeaving(false)}>Keep editing</button>
          <button class="danger" onClick={cancelEdits}>Discard</button><button class="filled" onClick={saveEdits}>Save</button></>}>
        You've changed some scores on this {what}. Save them, or put the scores back as they were?
      </Dialog>
      <Dialog open={overrideOpen} title="Override score" onClose={() => setOverrideOpen(false)}
        actions={<><button onClick={() => setOverrideOpen(false)}>Cancel</button>
          <button disabled={overrideText === ''} onClick={() => { const v = Number(overrideText); setOverrideOpen(false); if (Number.isInteger(v) && v >= 0) edit(x => withOverride(x, v)) }}>Save</button></>}>
        <p>For non-standard scoring. The scorecard will show this target as overridden.</p>
        <label class="field">Score<input inputMode="numeric" value={overrideText} onInput={e => setOverrideText((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))} /></label>
      </Dialog>
      <Dialog open={ask === 'finish'} title={inGroup ? 'Finish group round?' : 'Finish round?'} onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Cancel</button><button onClick={finish}>Finish</button></>}>
        {inGroup
          ? `${left > 0 ? `${left} score(s) still to enter across the group. ` : ''}Everyone's round is finished${friends > 0 ? "; friends' rounds are sent to them to accept" : ''}. You can reopen it later if needed.`
          : unscored > 0 ? `${unscored} target(s) aren't scored yet. Total ${roundTotal(round)}.` : `Total ${roundTotal(round)}. You can reopen it later if needed.`}
      </Dialog>
      <Dialog open={ask === 'delete'} title={inGroup ? 'Delete group round?' : 'Delete round?'} onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Cancel</button><button class="danger" onClick={async () => { setAsk(null); await deleteRound(round); replace('/') }}>Delete</button></>}>
        {inGroup ? "This removes everyone's round in the group and all their scores. Requests already sent to friends are taken back." : 'This removes the round and all its scores.'}
      </Dialog>
      <Dialog open={ask === 'takeover'} title="Take over as host?" onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Cancel</button><button onClick={async () => {
          setAsk(null)
          try { await live.takeOver(s!.groupId); toast("You're the host now.") } catch (e) { toast(errorText(e)) }
        }}>Take over</button></>}>
        Only if the host's phone has stopped working or gone. Your phone keeps the group going: the others follow it, the host's card is scored by the rest of you, and who scores which card may be shared out again. The host can join again with the code on any phone to get their card back.
      </Dialog>
      <Dialog open={ask === 'card'} title={`${typeAndClass(round)} · ${ukDate(round.shootDate)}`} onClose={() => setAsk(null)}
        actions={<><button onClick={() => { setAsk(null); go(`/card/${round.id}`) }}>Full scorecard</button><button onClick={() => setAsk(null)}>Close</button></>}>
        <div class="col">
          <RunningStats s={roundStats(shown)} targetCount={round.targetCount} />
          <div>{Array.from({ length: round.targetCount }, (_, i) => target(shown, i + 1)).map(x => (
            <TargetRow t={x} current={x.targetNo === n} locked={!canGoTo(shown, x.targetNo)} onGoTo={() => { setN(x.targetNo); setAsk(null) }} />
          ))}</div>
          {!finished && <button class="filled wide" onClick={() => setAsk('finish')}>Finish round</button>}
        </div>
      </Dialog>
    </div>
  )
}

function TargetMenu({ t, onOverride, onClear }: { t: TargetScore; onOverride: () => void; onClear: () => void }) {
  return <Menu label="Target options" items={[
    { text: t.overridePoints != null ? 'Edit override' : 'Override score', onClick: onOverride },
    { text: 'Clear target', onClick: onClear },
  ]} />
}

function Overridden({ t, onEdit, onRemove }: { t: TargetScore; onEdit: () => void; onRemove: () => void }) {
  return (
    <div class="override-note">
      <span class="grow">Overridden to {t.overridePoints} (calculated {calculatedPoints(t)})</span>
      <button onClick={onEdit}>Edit</button><button onClick={onRemove}>Remove</button>
    </div>
  )
}

/** "20 / 24", scored or not, the target type and options; then a scorer per animal. */
/**
 * A fun shot (3.1, Pete): a shot with unusual scoring that a club adds. Its score is typed in; it counts in the
 * round total but in no stats.
 */
function FunScorer({ t, onScore }: { t: TargetScore; onScore: (points: number | null) => void }) {
  const [text, setText] = useState(t.overridePoints != null ? String(t.overridePoints) : '')
  useEffect(() => { setText(t.overridePoints != null ? String(t.overridePoints) : '') }, [t.targetNo, t.overridePoints])
  const v = text === '' ? null : Number(text)
  const ok = v != null && Number.isInteger(v) && v >= 0 && v <= FUN_MAX
  return (
    <div class="col" style="gap:8px">
      <div class="sub">A shot with its own scoring. Type the score: it counts in the round total but not in any stats or handicaps.</div>
      <label class="field">Fun shot score<input inputMode="numeric" value={text} placeholder="e.g. 30"
        onInput={e => setText((e.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 3))}
        onKeyDown={e => { if (e.key === 'Enter' && ok) onScore(v) }} /></label>
      <div class="row">
        <button class="filled" disabled={!ok || v === t.overridePoints} onClick={() => onScore(v)}>{t.overridePoints != null ? 'Change score' : 'Save score'}</button>
        {t.overridePoints != null && <button onClick={() => onScore(null)}>Clear</button>}
      </div>
    </div>
  )
}

function TargetEditor({ t, onType, onResult, onOverride, onRemoveOverride, onClear, onFun }: {
  t: TargetScore; onType: (type: TargetType, fan?: number) => void; onResult: (i: number, r: AnimalResult) => void
  onOverride: () => void; onRemoveOverride: () => void; onClear: () => void; onFun: (points: number | null) => void
}) {
  const multi = t.animals.length > 1
  return (
    <>
      <div class="target-head">
        {/* 2.7.04 (Pete): no "20 / 24 · Target scored"; it read like the target number, and Next waits for a scored target anyway. */}
        <div class="grow" />
        <select class="chip-select" aria-label="Change target type" value={t.type} onChange={e => {
          const v = (e.target as HTMLSelectElement).value as TargetType
          if (v !== t.type) onType(v, MIN_FAN)
        }}>
          {(['STANDARD', 'PREDATOR_PREY', 'FAN', 'FUN'] as TargetType[]).map(x => <option value={x}>{TARGET_TYPE_NAMES[x]}</option>)}
        </select>
        {t.type !== 'FUN' && <TargetMenu t={t} onOverride={onOverride} onClear={onClear} />}
      </div>
      {t.type === 'FUN' && <FunScorer t={t} onScore={onFun} />}
      {t.type === 'FAN' && (
        <div class="stepper">
          <span class="grow">Animals in fan</span>
          <button disabled={t.animals.length <= MIN_FAN} onClick={() => onType('FAN', t.animals.length - 1)}>–</button>
          <b class="fan-count">{t.animals.length}</b>
          <button disabled={t.animals.length >= MAX_FAN} onClick={() => onType('FAN', t.animals.length + 1)}>+</button>
        </div>
      )}
      {t.type === 'FUN' ? null : t.overridePoints != null ? <Overridden t={t} onEdit={onOverride} onRemove={onRemoveOverride} />
        : t.animals.map((a, i) => {
          const waiting = t.type === 'PREDATOR_PREY' && i === 1 && t.animals[0].state === 'PENDING'
          const body = <AnimalScorer result={a} available={arrowsAvailable(t.type, t.animals, i)} waiting={waiting} onResult={r => onResult(i, r)} />
          return multi
            ? <div class="animal-card"><div class="row between"><b>{animalLabel(t, i)}</b><b class="animal-pts">{animalPoints(a)}</b></div><hr style="border:none;border-top:1px solid var(--outline-v);margin:0" />{body}</div>
            : body
        })}
    </>
  )
}

const HITS: Hit[] = ['INNER_KILL', 'KILL', 'WOUND']

/** Points tiles laid out like a Big Game card: inner kill, kill, wound across; 1st, 2nd, 3rd arrow down; miss below. */
function AnimalScorer({ result, available, waiting, onResult }: { result: AnimalResult; available: number; waiting: boolean; onResult: (r: AnimalResult) => void }) {
  if (waiting) return <div class="sub">Score the predator first</div>
  if (available === 0) return <div class="sub">No arrows left for this animal</div>
  return (
    <div class="col" style="gap:8px">
      <div class="scorer-grid">
        {[1, 2, 3].filter(a => a <= available).map(a => <>
          <span class="ord">{ordinal(a)}</span>
          {HITS.map(h => {
            if (a > 1 && h === 'INNER_KILL') return <span />
            const r: AnimalResult = { state: 'SCORED', arrowNo: a, hit: h }
            const on = sameResult(result, r)
            return <button class={`tile-score ${on ? 'on' : ''}`} aria-pressed={on} onClick={() => onResult(r)}>
              <b>{bigGamePoints(a, h)}</b><span>{HIT_NAMES[h]}</span></button>
          })}
        </>)}
      </div>
      <button class={`miss-tile ${result.state === 'MISSED' ? 'on' : ''}`} aria-pressed={result.state === 'MISSED'} onClick={() => onResult({ state: 'MISSED' })}>Miss · 0</button>
    </div>
  )
}

const ARROW_NAMES: Record<string, string> = { K: 'Kill', W: 'Wound', M: 'Miss' }

/** Woodsman: one animal, each arrow in order until a kill or 3 arrows; the next arrow's tiles show what it scores. */
function WoodsmanEditor({ t, onArrow, onUndo, onOverride, onRemoveOverride, onClear }: {
  t: TargetScore; onArrow: (a: 'K' | 'W' | 'M') => void; onUndo: () => void; onOverride: () => void; onRemoveOverride: () => void; onClear: () => void
}) {
  const done = woodsmanFinished(t.arrows)
  const nextNo = t.arrows.length + 1
  return (
    <>
      <div class="target-head">
        {/* 2.7.06: no "Target N" or "Woodsman" here; the top bar already says both */}
        <div class="grow sub">Shoot until you kill it, up to 3 arrows</div>
        <TargetMenu t={t} onOverride={onOverride} onClear={onClear} />
      </div>
      {t.overridePoints != null ? <Overridden t={t} onEdit={onOverride} onRemove={onRemoveOverride} /> : <>
        <div class="arrows3">
          {[0, 1, 2].map(i => {
            const a = t.arrows[i]
            return <div class={a ?? ''}><span>{ordinal(i + 1)} arrow</span><b>{a ? ARROW_NAMES[a] : done ? '–' : ''}</b></div>
          })}
        </div>
        {!done ? <>
          <h3 class="section">{ordinal(nextNo)} arrow</h3>
          <div class="row" style="flex-wrap:nowrap">
            <button class="tile-score grow" onClick={() => onArrow('K')}><b>{WOODSMAN_MAX - (nextNo - 1) * 10}</b><span>Kill</span></button>
            <button class="tile-score grow" onClick={() => onArrow('W')}><b>5</b><span>Wound</span></button>
          </div>
          <button class="miss-tile" onClick={() => onArrow('M')}>Miss · 0</button>
        </> : <div class="sub">{t.arrows.endsWith('K') ? `Killed with the ${ordinal(t.arrows.length)} arrow.` : 'All three arrows shot.'}</div>}
        {t.arrows && <button style="align-self:flex-start" onClick={onUndo}>Undo last arrow</button>}
      </>}
    </>
  )
}
