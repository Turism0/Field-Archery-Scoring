import { useMemo, useState } from 'preact/hooks'
import qrcode from 'qrcode-generator'
import { Dialog } from './common'
import { QrScanner } from './QrScanner'
import * as live from '../data/live'
import { checkProblem, compareCheck, decodeCheck, encodeCheck, short, type CheckResult } from '../domain/liveCheck'
import { describeAnimal, describeArrows, totalPoints, type TargetScore } from '../domain/scoring'

type Outcome = { problem: string } | { other: string; result: CheckResult }

/**
 * Double / 3+ scorers with no signal (web 2.8, as the app): every 5 targets entered here and not compared, a prompt to
 * read the scores out with the other scorer, or check them by QR (one phone shows its QR, the other scans it, then the
 * other way round). Matching ones are saved here as checked; nothing is copied between the phones.
 * Returns the dialogs and the ⋮ menu items ("Show my check QR", "Scan their check QR").
 */
export function useLiveCheck(s: live.LiveState | null, gid: string | undefined, onChanged: () => void) {
  const [snoozed, setSnoozed] = useState<{ targets: string; mine: number } | null>(null)
  const [showQr, setShowQr] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [outcome, setOutcome] = useState<Outcome | null>(null)

  const can = !!s && !!gid && s.offline && live.checked(s) && s.status === 'STARTED'
  const due = can ? live.dueManualCheck(s!) : []
  const mineCount = s ? s.entries.filter(e => e.scorerMemberId === s.myMemberId).length : 0
  const prompt = due.length > 0 && !(snoozed && snoozed.targets === due.join(',') && snoozed.mine >= mineCount)
  const waiting = s && due.length ? live.waitingForCheck(s, { targets: due }) : {}
  const cards = [...new Set(Object.keys(waiting).map(k => k.slice(k.indexOf('|') + 1)))]
    .sort((a, b) => (live.member(s!, a)?.sortOrder ?? 0) - (live.member(s!, b)?.sortOrder ?? 0))
  const others = s ? live.otherScorerNames(s, cards.length ? cards : Object.keys(live.waitingForCheck(s)).map(k => k.slice(k.indexOf('|') + 1))) : []
  const who = others.join(' and ') || 'the other scorer'
  const name = (id: string) => (id === s?.myMemberId ? `${live.member(s!, id)?.name ?? 'You'} (you)` : live.member(s!, id)?.name ?? '?')

  const qrEntries = s && showQr ? live.waitingForCheck(s, { includeChecked: true }) : {}
  const qrCount = Object.keys(qrEntries).length
  const qrSvg = useMemo(() => {
    if (!s || !showQr || !qrCount) return ''
    const text = encodeCheck(s.groupId, s.myMemberId, Object.entries(qrEntries).map(([k, target]) => ({ card: k.slice(k.indexOf('|') + 1), target })))
    const q = qrcode(0, 'M'); q.addData(text); q.make()
    return q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })
  }, [showQr, JSON.stringify(qrEntries)])

  async function agree() {
    await live.markChecked(gid!, Object.keys(waiting))
    onChanged()
  }

  async function scanned(text: string) {
    setScanning(false)
    const st = s!
    const p = decodeCheck(text)
    if (!p) {
      setOutcome({ problem: live.isJoinQr(text) ? "That's a group's join QR. Scan the QR the other scorer shows with Show my QR." : "That isn't a score check QR. Scan the QR the other scorer shows with Show my QR." })
      return
    }
    const mine = live.waitingForCheck(st)
    const mineCards = [...new Set(Object.keys(mine).map(k => k.slice(k.indexOf('|') + 1)))]
    if (!mineCards.length) { setOutcome({ problem: 'Nothing on this phone is waiting to be checked.' }); return }
    const problem = checkProblem(p, st.groupId, st.myMemberId, mineCards, c => live.member(st, c)?.scorers ?? [])
    if (problem) { setOutcome({ problem }); return }
    const result = compareCheck(mine, p)
    if (result.matched.length) { await live.markChecked(gid!, result.matched); onChanged() }
    setOutcome({ other: st.members.find(m => short(m.memberId) === p.scorer)?.name ?? 'the other scorer', result })
  }

  const what = (t: TargetScore) => t.overridePoints != null ? 'overridden' : t.type === 'WOODSMAN' ? describeArrows(t.arrows) : t.animals.map(describeAnimal).join('; ')
  const card = (id: string) => (id === s?.myMemberId ? 'you' : live.member(s!, id)?.name ?? '?')
  const range = due.length && due.every((x, i) => i === 0 || x === due[i - 1] + 1) ? `targets ${due[0]}–${due[due.length - 1]}` : `targets ${due.join(', ')}`

  const menu = can ? [
    { text: 'Show my check QR', onClick: () => setShowQr(true) },
    { text: 'Scan their check QR', onClick: () => setScanning(true) },
  ] : []

  const dialogs = <>
    <Dialog open={prompt && !showQr && !scanning && !outcome} title={`Check with ${who}`} onClose={() => setSnoozed({ targets: due.join(','), mine: mineCount })}
      actions={<><button onClick={() => setSnoozed({ targets: due.join(','), mine: mineCount })}>Not now</button><button onClick={agree}>They agree</button></>}>
      <div class="col">
        <div>There's no signal, so these haven't been compared with {who}'s. Read out your scores for {range} and check they agree.</div>
        {cards.map(c => {
          const pts = due.map(n => waiting[`${n}|${c}`])
          return <b>{name(c)}: {pts.reduce((x, t) => x + (t ? totalPoints(t) : 0), 0)} ({pts.map(t => (t ? totalPoints(t) : '–')).join(', ')})</b>
        })}
        <div class="sub">If one differs, agree it, put it right on that target, then check again. Agreed scores are saved here and compared with {who}'s when there's signal.</div>
        <div class="sub">Or check by QR: scan {who}'s QR, and let them scan yours.</div>
        <div class="row"><button class="outlined" onClick={() => setShowQr(true)}>Show my QR</button><button class="outlined" onClick={() => setScanning(true)}>Scan their QR</button></div>
      </div>
    </Dialog>
    <Dialog open={showQr} title="My check QR" onClose={() => setShowQr(false)}
      actions={<><button onClick={() => setShowQr(false)}>Done</button><button onClick={() => { setShowQr(false); setScanning(true) }}>Scan theirs</button></>}>
      <div class="col">
        <div>{qrCount ? `${qrCount} score${qrCount === 1 ? '' : 's'} waiting to be checked. ${who[0].toUpperCase() + who.slice(1)} scans this with Scan their QR; then scan theirs, so both phones are checked.` : 'Nothing on this phone is waiting to be checked.'}</div>
        {qrCount > 0 && <div class="qr" style="width:min(300px,100%);align-self:center" dangerouslySetInnerHTML={{ __html: qrSvg }} />}
      </div>
    </Dialog>
    <QrScanner open={scanning} onClose={() => setScanning(false)} onResult={scanned} hint={`Point the camera at the check QR on ${who}'s phone.`} />
    <Dialog open={!!outcome} onClose={() => setOutcome(null)}
      title={!outcome ? '' : 'problem' in outcome ? "Couldn't check" : outcome.result.differences.length ? "Scores don't match"
        : outcome.result.notInTheirs.length ? `${outcome.result.matched.length} match` : `All ${outcome.result.matched.length} match`}
      actions={<>{outcome && ('problem' in outcome || outcome.result.differences.length > 0) && <button onClick={() => { setOutcome(null); setScanning(true) }}>Scan again</button>}
        <button onClick={() => setOutcome(null)}>OK</button></>}>
      {outcome && ('problem' in outcome ? <div>{outcome.problem}</div> : <div class="col">
        {outcome.result.matched.length > 0 && <div>{outcome.result.matched.length} score{outcome.result.matched.length === 1 ? '' : 's'} match {outcome.other}'s and {outcome.result.matched.length === 1 ? 'is' : 'are'} saved here. They're compared again when there's signal.</div>}
        {outcome.result.differences.length > 0 && <>
          <div>These differ. Agree them, put them right on the target, then check again:</div>
          {outcome.result.differences.map(d => <b>Target {d.targetNo}, {card(d.cardMemberId)}: you {totalPoints(d.mine)} ({what(d.mine)}), {outcome.other} {totalPoints(d.theirs)} ({what(d.theirs)})</b>)}
        </>}
        {outcome.result.notInTheirs.length > 0 && <div class="sub">Not entered on {outcome.other}'s phone yet: {outcome.result.notInTheirs.map(k => `Target ${k.split('|')[0]}, ${card(k.slice(k.indexOf('|') + 1))}`).join('; ')}</div>}
      </div>)}
    </Dialog>
  </>
  return { dialogs, menu }
}
