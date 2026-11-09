import { useEffect, useMemo, useState } from 'preact/hooks'
import { Bar, Dialog, errorText, go, replace, toast, useData } from './common'
import { getRound } from '../data/db'
import { groupOf, saveTarget, setStatus, setTypeForGroup, syncNow } from '../data/rounds'
import * as live from '../data/live'
import { canGoTo, firstUnscored, roundTotal, target, targetsComplete, type Round } from '../domain/round'
import {
  HIT_NAMES, MAX_FAN, MIN_FAN, TARGET_TYPE_NAMES, allowedHits, animalLabel, arrowsAvailable, cleared, isComplete, sameResult,
  totalPoints, withAnimalResult, withArrow, withOverride, withType, withoutLastArrow, type AnimalResult, type TargetScore, type TargetType,
} from '../domain/scoring'

/** Scoring a round target by target; in a group, archer by archer on each target. */
export function Scoring({ id, target: startTarget }: { id: string; target?: number }) {
  const [current, setCurrent] = useState(id)
  const round = useData(() => getRound(current), [current])
  const group = useData(async () => { const r = await getRound(id); return r ? groupOf(r) : [] }, [id]) ?? []
  const [n, setN] = useState(startTarget ?? 0)
  const [overrideOpen, setOverrideOpen] = useState(false)
  const [overrideText, setOverrideText] = useState('')
  const [finishOpen, setFinishOpen] = useState(false)

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
      catch (e) { if (alive) setProblem(errorText(e)) }
    }
    tick()
    const t = setInterval(tick, 3000)
    return () => { alive = false; clearInterval(t) }
  }, [gid, hasInfo])

  useEffect(() => { if (round && (n === 0 || !canGoTo(round, n))) setN(firstUnscored(round) ?? 1) }, [round?.id])

  const isLive = !!info
  const s = state
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

  if (!round || !shown || n === 0) return <Bar title="Scoring" onBack={() => go('/')} />
  const t = target(shown, n)
  const me = group.find(m => m.id === round.id)
  const name = me?.groupRole && me.groupRole !== 'SCORER' ? me.groupName : group.length > 1 ? 'You' : null
  const whyNot = !isLive ? null : !s ? 'Connecting to the group…' : s.status === 'CLOSED' ? 'The group is closed.'
    : s.status !== 'STARTED' ? 'Waiting for the host to start.' : canScore ? null
    : (() => { const who = live.scorerNames(s, current); return who.length ? `${who.join(' and ')} ${who.length > 1 ? 'score' : 'scores'} this card.` : 'Someone else scores this card.' })()
  const check = s ? live.checkText(s, n, current, canScore && !!mine[`${n}|${current}`]) : null
  const mismatch = !!s && live.cell(s, n, current) === 'MISMATCH'

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
    if (group.length > 1 && (!isLive || (s && !checked && s.members.every(m => live.canScore(s, m.memberId))))) {
      await setTypeForGroup(round!, withType(t, type, fan))
    }
  }

  const toggle = (i: number, a: AnimalResult) => edit(x => withAnimalResult(x, i, sameResult(x.animals[i], a) ? { state: 'PENDING' } : a))

  /** Archer by archer: next archer on this target, then the first archer on the next target. */
  function step(by: 1 | -1) {
    if (group.length > 1) {
      const i = group.findIndex(m => m.id === current)
      let j = i + by, nn = n
      if (j >= group.length) { j = 0; nn = n + 1 } else if (j < 0) { j = group.length - 1; nn = n - 1 }
      if (nn < 1 || nn > round!.targetCount) return
      setCurrent(group[j].id); setN(nn)
    } else {
      const nn = n + by
      if (canGoTo(shown!, nn)) setN(nn)
    }
  }

  const allDone = group.every(m => targetsComplete(m) === m.targetCount)

  return (
    <>
      <Bar title={`Target ${n} of ${round.targetCount}${name ? ` · ${name}` : ''}`} onBack={() => go('/')}>
        <button onClick={() => go(`/card/${round.id}`)}>Card</button>
      </Bar>
      <main>
        {isLive && (
          <div class={`banner ${problem || mismatch ? 'bad' : ''}`}>
            <b>Live group{s ? ` · ${s.code} · ${s.status === 'CLOSED' ? 'finished' : s.status === 'STARTED' ? live.methodOf(s.method)?.name : 'waiting to start'}` : ''}</b>
            {problem && <span>Can't reach the server ({problem}). Keep scoring: scores are sent when it's back.</span>}
            {whyNot && <span>{whyNot}</span>}
            {check && <span style={mismatch ? 'font-weight:600' : ''}>{check}</span>}
            {s && s.status === 'OPEN' && <button onClick={() => go(`/lobby/${s.groupId}`)}>{live.isHost(s) ? 'Show code and start' : 'Show the group'}</button>}
            {s && s.status === 'STARTED' && live.canTakeOver(s) && <button onClick={async () => {
              if (confirm("Take over as host? Only if the host's phone has stopped working or gone.")) {
                try { await live.takeOver(s.groupId); toast("You're the host now.") } catch (e) { toast(errorText(e)) }
              }
            }}>Host's phone gone? Take over</button>}
          </div>
        )}
        {group.length > 1 && (
          <div class="strip">
            {group.map(m => (
              <button class={m.id === current ? 'on' : ''} onClick={() => { setCurrent(m.id); if (!canGoTo(m, n)) setN(firstUnscored(m) ?? n) }}>
                {m.groupRole === 'SCORER' ? 'You' : m.groupName}<br /><b>{roundTotal(m)}</b> {isComplete(target(m, n)) ? '✓' : ''}
              </button>
            ))}
          </div>
        )}
        <div class="row between">
          <div><div class="sub">Round total</div><div class="big">{roundTotal(shown)}</div></div>
          <div style="text-align:right"><div class="sub">This target</div><div class="big">{totalPoints(t)}</div></div>
        </div>

        {round.type === 'BIG_GAME' && (
          <div class="card low" style={canScore ? '' : 'opacity:.55'}>
            <div class="row">
              {(['STANDARD', 'PREDATOR_PREY', 'FAN'] as TargetType[]).map(tt => (
                <button class={`chip ${t.type === tt ? 'on' : ''}`} onClick={() => setType(tt)}>{TARGET_TYPE_NAMES[tt]}</button>
              ))}
            </div>
            {t.type === 'FAN' && (
              <div class="row">Animals:
                {Array.from({ length: MAX_FAN - MIN_FAN + 1 }, (_, i) => i + MIN_FAN).map(k =>
                  <button class={`chip ${t.animals.length === k ? 'on' : ''}`} onClick={() => setType('FAN', k)}>{k}</button>)}
              </div>
            )}
            {t.overridePoints != null
              ? <div class="row between"><span>Score entered by hand: <b>{t.overridePoints}</b></span><button onClick={() => edit(x => withOverride(x, null))}>Remove</button></div>
              : t.animals.map((a, i) => <AnimalPanel t={t} index={i} result={a} onPick={r => toggle(i, r)} />)}
          </div>
        )}

        {round.type === 'WOODSMAN' && (
          <div class="card low" style={canScore ? '' : 'opacity:.55'}>
            <div>Arrows: <b>{t.arrows ? [...t.arrows].map(c => ({ W: 'Wound', K: 'Kill', M: 'Miss' })[c]).join(', ') : 'none yet'}</b></div>
            {t.overridePoints != null
              ? <div class="row between"><span>Score entered by hand: <b>{t.overridePoints}</b></span><button onClick={() => edit(x => withOverride(x, null))}>Remove</button></div>
              : <div class="wood">
                  <button onClick={() => edit(x => withArrow(x, 'W'))}>Wound</button>
                  <button onClick={() => edit(x => withArrow(x, 'K'))}>Kill</button>
                  <button onClick={() => edit(x => withArrow(x, 'M'))}>Miss</button>
                </div>}
            {t.arrows && <button onClick={() => edit(withoutLastArrow)}>Undo last arrow</button>}
          </div>
        )}

        <div class="row">
          <button onClick={() => { setOverrideText(String(totalPoints(t))); setOverrideOpen(true) }}>Enter a score by hand</button>
          <button onClick={() => edit(cleared)}>Clear target</button>
        </div>
        {allDone && round.status === 'IN_PROGRESS' && <button class="filled wide" onClick={() => setFinishOpen(true)}>Finish {group.length > 1 ? 'group round' : 'round'}</button>}
      </main>

      <div class="nav">
        <button class="tonal" onClick={() => step(-1)}>‹ Previous</button>
        <button class="filled" onClick={() => step(1)}>Next ›</button>
      </div>

      <Dialog open={overrideOpen} title="Score by hand" onClose={() => setOverrideOpen(false)}
        actions={<><button onClick={() => setOverrideOpen(false)}>Cancel</button>
          <button onClick={() => { const v = Number(overrideText); setOverrideOpen(false); if (Number.isInteger(v) && v >= 0) edit(x => withOverride(x, v)) }}>Save</button></>}>
        <p class="sub">For a target scored some other way. Replaces the hits on this target.</p>
        <input inputMode="numeric" value={overrideText} onInput={e => setOverrideText((e.target as HTMLInputElement).value.replace(/\D/g, ''))} />
      </Dialog>
      <Dialog open={finishOpen} title={group.length > 1 ? 'Finish group round?' : 'Finish round?'} onClose={() => setFinishOpen(false)}
        actions={<><button onClick={() => setFinishOpen(false)}>Cancel</button><button onClick={async () => {
          setFinishOpen(false)
          if (isLive && gid && s && live.isHost(s)) { try { await live.close(gid) } catch (e) { toast(`Couldn't close the group (${errorText(e)}).`) } }
          await setStatus(round, 'COMPLETED')
          syncNow()   // backed up straight away if there's signal
          replace(`/card/${round.id}`)
        }}>Finish</button></>}>
        Total {roundTotal(round)}. {group.length > 1 ? "Everyone's round is finished; friends' rounds are sent to them to accept. " : ''}You can reopen it later if needed.
      </Dialog>
    </>
  )
}

/** One animal: arrow 1 inner kill / kill / wound, arrows 2 and 3 kill / wound, or miss. Tap again to clear. */
function AnimalPanel({ t, index, result, onPick }: { t: TargetScore; index: number; result: AnimalResult; onPick: (r: AnimalResult) => void }) {
  const available = arrowsAvailable(t.type, t.animals, index)
  const on = (r: AnimalResult) => sameResult(result, r)
  return (
    <div class="animal">
      {t.animals.length > 1 && <b>{animalLabel(t, index)}</b>}
      {available === 0 ? <div class="sub">No arrows left for this animal.</div> : (
        <div class="hits">
          {[1, 2, 3].filter(a => a <= available).map(a => <>
            <span>Arrow {a}</span>
            {allowedHits(a).length === 2 && <span />}
            {allowedHits(a).map(h => {
              const r: AnimalResult = { state: 'SCORED', arrowNo: a, hit: h }
              return <button class={on(r) ? 'on' : ''} aria-pressed={on(r)} onClick={() => onPick(r)}>{HIT_NAMES[h]}</button>
            })}
          </>)}
          <span />
          <button class={`miss ${on({ state: 'MISSED' }) ? 'on' : ''}`} onClick={() => onPick({ state: 'MISSED' })}>Miss</button>
        </div>
      )}
    </div>
  )
}
