import { useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Loading, back, errorText, go, one, toast, ukDate, useData, useLoad } from './common'
import { getAccount } from '../data/account'
import { getModel } from '../data/handicap'
import * as friends from '../data/friends'
import { personalHandicaps } from '../domain/handicap'
import { bowName } from '../domain/round'
import { ReadOnlySheet } from './Scorecard'

/** Friends: add by username, requests, rounds scored for you, and each friend's rounds. */
export function Friends() {
  const account = useData(getAccount)
  const [data, retry] = useLoad(async () => account ? { o: await friends.overview(), offers: await friends.offers() } : null, [account?.token])
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)

  async function act(f: () => Promise<unknown>, done?: string) {
    setBusy(true)
    try { await f(); if (done) toast(done); retry() } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }

  if (account === undefined) return <Bar title="Friends" />
  if (!account) return <>
    <Bar title="Friends" />
    <main><Card title="Sign in to use Friends" sub="Friends can see each other's finished scorecards and stats.">
      <button class="filled" onClick={() => go('/profile')}>Go to Profile</button></Card></main>
  </>

  return (
    <>
      <Bar title="Friends" />
      <main>
        <Loading load={data} retry={retry}>{v => v && <div class="split"><div>
        <Card title="Add a friend" sub="They'll see your finished rounds once they accept, and you'll see theirs.">
          <Field label="Their username"><input autocapitalize="off" value={name} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>
          <button class="filled" disabled={busy || !name.trim()} onClick={() => act(async () => { toast(await friends.sendRequest(name)); setName('') })}>Send friend request</button>
        </Card>
          {v.offers.length > 0 && <>
            <h3 class="section">Rounds scored for you</h3>
            {v.offers.map(o => (
              <Card>
                <b>{o.totalPoints} points · {ukDate(o.shootDate)}</b>
                <div class="sub">Scored by {o.fromUsername} · {o.classLine} · {o.targetCount} targets{o.locationName ? ` · ${o.locationName}` : ''}</div>
                <div class="row">
                  <button class="outlined" onClick={() => go(`/offer/${o.roundId}`)}>Scorecard</button>
                  <button disabled={busy} onClick={() => act(() => friends.declineOffer(o.roundId), 'Declined.')}>Decline</button>
                  <button class="filled" disabled={busy} onClick={() => act(() => friends.acceptOffer(o.roundId), 'Added to your rounds.')}>Accept</button>
                </div>
              </Card>
            ))}
          </>}
          {v.o.incoming.length > 0 && <>
            <h3 class="section">Friend requests</h3>
            {v.o.incoming.map(r => (
              <div class="card row between"><b>{r.username}</b><span>
                <button disabled={busy} onClick={() => act(() => friends.removeFriend(r.archerId))}>Decline</button>
                <button class="filled" disabled={busy} onClick={() => act(() => friends.acceptFriend(r.archerId), `You and ${r.username} are now friends.`)}>Accept</button></span></div>
            ))}
          </>}
          {v.o.outgoing.length > 0 && <>
            <h3 class="section">Waiting for them to accept</h3>
            {v.o.outgoing.map(r => (
              <div class="card row between"><b>{r.username}</b>
                <button disabled={busy} onClick={() => act(() => friends.removeFriend(r.archerId))}>Cancel</button></div>
            ))}
          </>}
          </div><div>
          <h3 class="section">Friends</h3>
          {!v.o.friends.length && <p class="sub">No friends yet. Add one by username above.</p>}
          {v.o.friends.map(f => {
            const hs = personalHandicaps(f.recent)
            const lines = [
              f.rounds > 0 ? `${f.rounds} round${f.rounds === 1 ? '' : 's'}${f.bestTotal != null ? ` · best ${f.bestTotal}` : ''}${f.averagePerAnimal != null ? ` · avg ${one(f.averagePerAnimal)} per animal` : ''}` : '',
              f.woodsmanRounds > 0 ? `Woodsman: ${f.woodsmanRounds} round${f.woodsmanRounds === 1 ? '' : 's'}${f.woodsmanBest != null ? ` · best ${f.woodsmanBest}` : ''}` : '',
            ].filter(Boolean)
            return (
              <button class="tile" onClick={() => go(`/friend/${f.archerId}?name=${encodeURIComponent(f.username)}`)}>
                <span class="grow"><b>{f.username}</b>
                  {lines.length ? lines.map(l => <span class="sub" style="display:block">{l}</span>) : <span class="sub">No finished rounds yet</span>}
                  {f.lastShootDate && <span class="sub" style="display:block">Last shot {ukDate(f.lastShootDate)}</span>}</span>
                {/* Personal handicap (2.9): their main bow's, big; any others underneath. */}
                {hs.length > 0 && <span style="text-align:center"><span class="num-big">{hs[0].handicap}</span>
                  <span class="sub" style="display:block">handicap {hs[0].bowStyle}</span>
                  {hs.slice(1).map(h => <span class="sub" style="display:block">{h.bowStyle} {h.handicap}</span>)}</span>}
                <span>›</span>
              </button>
            )
          })}
        </div></div>}</Loading>
      </main>
    </>
  )
}

/** A friend's finished rounds (and their personal handicaps, 2.9). */
export function FriendRounds({ id, name }: { id: string; name: string }) {
  const [data, retry] = useLoad(() => friends.friendRounds(id), [id])
  const hs = useData(async () => { try { return personalHandicaps((await friends.overview()).friends.find(f => f.archerId === id)?.recent ?? []) } catch { return [] } }, [id]) ?? []
  const [ask, setAsk] = useState(false)
  return (
    <>
      <Bar title={name}><button class="danger" onClick={() => setAsk(true)}>Remove friend</button></Bar>
      <main>
        <Loading load={data} retry={retry}>{list => {
          return <>
            {hs.length > 0 && <Card cls="hi" title="Handicap">
              {hs.map(h => <div class="row between"><span>{bowName(h.bowStyle)}</span><b class="big">{h.handicap}</b></div>)}
              <div class="sub" style="color:inherit">Par 960 (40 targets) minus their usual score: the best {hs[0].counted.length} of their last {hs[0].considered} {bowName(hs[0].bowStyle)} rounds{hs.length > 1 ? ', and the same for each bow' : ''}.</div>
            </Card>}
            <h3 class="section">Finished rounds</h3>
            {!list.length && <p class="sub">No finished rounds yet.</p>}
            <div class="grid2">{list.map(r => (
              <button class="tile" onClick={() => go(`/friendcard/${id}/${r.roundId}?name=${encodeURIComponent(name)}`)}>
                <span class="grow"><b>{ukDate(r.shootDate)}</b>
                  <span class="sub" style="display:block">{r.classLine} · {r.targetsLine}{r.locationName ? ` · ${r.locationName}` : ''}</span>
</span>
                <span style="text-align:center"><span class="num-big">{r.totalPoints}</span><span class="sub" style="display:block">points</span></span>
              </button>
            ))}</div>
          </>
        }}</Loading>
      </main>
      <Dialog open={ask} title={`Remove ${name}?`} onClose={() => setAsk(false)}
        actions={<><button onClick={() => setAsk(false)}>Cancel</button>
          <button class="danger" onClick={async () => { setAsk(false); try { await friends.removeFriend(id); back() } catch (e) { toast(errorText(e)) } }}>Remove</button></>}>
        You'll stop seeing each other's rounds. You can send a new request later.
      </Dialog>
    </>
  )
}

export function FriendScorecard({ id, roundId, name }: { id: string; roundId: string; name: string }) {
  const model = useData(getModel)
  const [data, retry] = useLoad(() => friends.friendRound(id, roundId), [id, roundId])
  return <><Bar title={name ? `${name}'s scorecard` : 'Scorecard'} /><main><Loading load={data} retry={retry}>{r => <ReadOnlySheet round={r} model={model} />}</Loading></main></>
}

/** A round a friend scored for you: look, then accept or decline. */
export function Offer({ roundId }: { roundId: string }) {
  const model = useData(getModel)
  const [data, retry] = useLoad(() => friends.offerRound(roundId), [roundId])
  const [busy, setBusy] = useState(false)
  async function act(f: () => Promise<unknown>, msg: string) {
    setBusy(true)
    try { await f(); toast(msg); back() } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  return (
    <>
      <Bar title="Round scored for you" />
      <main><Loading load={data} retry={retry}>{v => <>
        {v.from && <div class="sub">by {v.from}</div>}
        <div class="row">
          <button disabled={busy} onClick={() => act(() => friends.declineOffer(roundId), 'Declined.')}>Decline</button>
          <button class="filled" disabled={busy} onClick={() => act(() => friends.acceptOffer(roundId), 'Added to your rounds.')}>Accept – add to my rounds</button>
        </div>
        <ReadOnlySheet round={v.round} model={model} />
      </>}</Loading></main>
    </>
  )
}
