import { useEffect, useMemo, useState } from 'preact/hooks'
import qrcode from 'qrcode-generator'
import { Bar, Card, Dialog, Seg, Select, errorText, go, replace, today, useData } from './common'
import { getAccount, getProfile } from '../data/account'
import { groupRounds } from '../data/db'
import { deleteRound } from '../data/rounds'
import * as live from '../data/live'
import { AGE_NAMES, BOW_STYLES, CLASS_NAMES, ageGroupFor, type AgeGroup, type ArcherClass } from '../domain/round'

/** Join a live group with its code (typed; the host's phone shows it). */
export function Join({ code: startCode }: { code: string }) {
  const account = useData(getAccount)
  const [code, setCode] = useState(live.cleanCode(startCode))
  const [cls, setCls] = useState<ArcherClass>()
  const [age, setAge] = useState<AgeGroup>()
  const [bow, setBow] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    getProfile().then(p => {
      setCls(p.archerClass); setBow(p.bowStyle)
      setAge(p.ageGroupManual ?? (p.dateOfBirth ? ageGroupFor(p.dateOfBirth, today()) ?? undefined : undefined))
    })
  }, [])
  const valid = code.length === 6 && cls && age && bow && !!account

  async function join() {
    setBusy(true); setError(null)
    try { replace(`/lobby/${await live.join(code, cls!, age!, bow!)}`) } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }

  return (
    <>
      <Bar title="Join a live group" />
      <main class="form">
        {!account && <div class="card err">Sign in (Profile) to join a live group.</div>}
        <Card title="Group code" sub="Type the 6-character code from the host's phone. The web app needs signal for live groups.">
          <input class="code" autocapitalize="characters" autocomplete="off" value={code} onInput={e => setCode(live.cleanCode((e.target as HTMLInputElement).value))} />
        </Card>
        <Card title="You" sub="From your profile. Changes here only affect this round.">
          <Seg<ArcherClass> options={['GENTS', 'LADIES']} value={cls} label={c => CLASS_NAMES[c]} onChange={setCls} />
          <Select<AgeGroup> label="Age group" value={age} options={['ADULT', 'JUNIOR', 'CUB']} optionLabel={a => AGE_NAMES[a]} onChange={setAge} />
          <Select label="Bow style" value={bow} options={BOW_STYLES.map(b => b.code)} optionLabel={c => `${BOW_STYLES.find(b => b.code === c)!.name} (${c})`} onChange={setBow} />
        </Card>
        {error && <div class="card err">{error}</div>}
        <button class="filled wide" disabled={!valid || busy} onClick={join}>{busy ? 'Joining…' : 'Join'}</button>
      </main>
    </>
  )
}

/** Before scoring starts: the code and QR, who's in, and (host) Start. Every phone opens scoring once the host starts. */
export function Lobby({ groupId }: { groupId: string }) {
  const info = useData(() => live.liveInfo(groupId), [groupId])
  const [s, setS] = useState<live.LiveState | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [ask, setAsk] = useState<'cancel' | 'early' | 'takeover' | null>(null)

  useEffect(() => {
    let alive = true
    const tick = async () => {
      try { const st = await live.refresh(groupId); if (alive) { setS(st); setProblem(null) } }
      catch (e) { if (alive) setProblem(errorText(e)) }
    }
    tick()
    const t = setInterval(tick, 3000)
    return () => { alive = false; clearInterval(t) }
  }, [groupId])

  // Started (or finished while away): straight to scoring.
  useEffect(() => {
    if (s && (s.status === 'STARTED' || s.status === 'CLOSED')) groupRounds(groupId).then(rs => {
      const mine = rs.find(r => r.id === s.myMemberId)
      if (mine) replace(`/score/${rs.sort((a, b) => (a.groupOrder ?? 0) - (b.groupOrder ?? 0))[0].id}`)
      else if (s.status === 'CLOSED') replace('/')
    })
  }, [s?.status])

  const qr = useMemo(() => {
    if (!s) return ''
    const q = qrcode(0, 'M'); q.addData(live.qrText(s.code)); q.make()
    return q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })
  }, [s?.code])

  async function act(f: () => Promise<void>) {
    setBusy(true)
    try { await f() } catch (e) { setProblem(errorText(e)) } finally { setBusy(false) }
  }

  if (!s) return <><Bar title="Live group" onBack={() => go('/')} /><main><p>{problem ? `Can't reach the server: ${problem}` : 'Connecting…'}</p></main></>
  const host = live.isHost(s)
  const expected = info?.expected ?? 0
  const everyoneIn = s.members.length >= expected

  return (
    <>
      <Bar title="Live group" onBack={() => go('/')} />
      <main class="form">
        <Card title="Join code" sub="Others tap Join a live group (web app) or Score a round → Join a live group (Android app), then type this or scan the QR.">
          <div class="code" style="font-size:40px;text-align:center">{s.code.slice(0, 3)} {s.code.slice(3)}</div>
          <div class="qr" style="width:200px" dangerouslySetInnerHTML={{ __html: qr }} />
        </Card>
        <Card title="Scoring" sub={`${live.methodOf(s.method)?.name}: ${live.methodOf(s.method)?.help}`}>
          <div>{s.roundType === 'WOODSMAN' ? 'Woodsman' : 'Big Game'} · {s.targetCount} targets{s.locationName ? ` · ${s.locationName}` : ''}</div>
        </Card>
        <Card title={expected ? `In the group (${s.members.length} of ${expected})` : `In the group (${s.members.length})`} sub="In scoring order.">
          {s.members.map(m => (
            <div class="row between"><span><b>{m.name}{m.memberId === s.myMemberId ? ' (you)' : ''}</b><div class="sub">{CLASS_NAMES[m.archerClass]} · {AGE_NAMES[m.ageGroup]} · {m.bowStyle}</div></span>
              <span class="sub">{({ HOST: 'Host', JOINED: 'Own phone', FRIEND: 'Friend', GUEST: 'Guest' } as Record<string, string>)[m.kind]}</span></div>
          ))}
        </Card>
        {problem && <div class="error">Can't reach the server ({problem}). Trying again…</div>}
        {host ? <>
          <button class="filled wide" disabled={busy || !everyoneIn} onClick={() => act(() => live.start(groupId))}>{busy ? 'Starting…' : 'Start scoring'}</button>
          {!everyoneIn && <><div>Waiting for {expected - s.members.length} more to join ({expected} in the group).</div>
            <button class="outlined" disabled={busy} onClick={() => setAsk('early')}>Start anyway</button></>}
          <button class="outlined" disabled={busy} onClick={() => setAsk('cancel')}>Cancel the group</button>
        </> : <>
          <div>Waiting for the host to start. Scoring opens by itself.</div>
          <button class="outlined" disabled={busy} onClick={() => setAsk('cancel')}>Leave the group</button>
          {live.canTakeOver(s) && <button disabled={busy} onClick={() => setAsk('takeover')}>Host's phone gone? Take over as host</button>}
        </>}
      </main>
      <Dialog open={ask === 'cancel'} title={host ? 'Cancel the group?' : 'Leave the group?'} onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Keep</button><button onClick={() => { setAsk(null); act(async () => {
          if (host) await live.close(groupId); else await live.leave(groupId, false)
          const rs = await groupRounds(groupId)
          if (rs[0]) await deleteRound(rs[0])
          replace('/')
        }) }}>{host ? 'Cancel group' : 'Leave'}</button></>}>
        {host ? "The code stops working and the group's rounds are deleted from this phone." : 'Your round in this group is deleted from this phone.'}
      </Dialog>
      <Dialog open={ask === 'early'} title="Start without everyone?" onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Wait</button><button onClick={() => { setAsk(null); act(() => live.start(groupId)) }}>Start</button></>}>
        {s.members.length} of {expected} are in: {s.members.map(m => m.name).join(', ')}. Anyone else can still join with the code.
      </Dialog>
      <Dialog open={ask === 'takeover'} title="Take over as host?" onClose={() => setAsk(null)}
        actions={<><button onClick={() => setAsk(null)}>Cancel</button><button onClick={() => { setAsk(null); act(() => live.takeOver(groupId)) }}>Take over</button></>}>
        Only if the host's phone has stopped working or gone. Your phone keeps the group going; the host can join again with the code on any phone.
      </Dialog>
    </>
  )
}
