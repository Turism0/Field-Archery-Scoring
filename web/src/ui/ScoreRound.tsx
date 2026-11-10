import { Bar, Card, go, useData } from './common'
import { getProfile } from '../data/account'
import { inProgress } from '../data/rounds'
import { allRounds } from '../data/db'
import { ROUND_TYPE_HELP, ROUND_TYPE_NAMES } from '../domain/round'
import type { RoundType } from '../domain/scoring'
import { RoundCard } from './Parts'

/** Score a round (as the app's): start a new round, join a live group, or continue one in progress. */
export function ScoreRound() {
  const going = useData(inProgress)
  const profile = useData(getProfile)
  const sizes = useData(async () => {
    const c: Record<string, number> = {}
    for (const r of await allRounds()) if (r.groupId) c[r.groupId] = (c[r.groupId] ?? 0) + 1
    return c
  }) ?? {}
  const ready = !profile || (!!profile.archerClass && !!profile.bowStyle)
  return (
    <>
      <Bar title="Score a round" />
      <main class="form">
        {ready ? <button class="filled wide" style="height:56px;font-size:17px" onClick={() => go('/type')}>＋ Start a new round</button>
          : <Card title="Set up your profile first" cls="err" sub="Your class and main bow style are filled in on each new round.">
              <button class="tonal" style="align-self:flex-start" onClick={() => go('/profile')}>Set up profile</button>
            </Card>}
        {ready && <button class="outlined wide" onClick={() => go('/join')}>Join a live group</button>}
        {going && going.length > 0 && <>
          <h3 class="section">Continue a round</h3>
          {going.map(r => <RoundCard r={r} groupSize={r.groupId ? sizes[r.groupId] ?? 1 : 1} onClick={() => go(`/score/${r.id}`)} />)}
        </>}
      </main>
    </>
  )
}

/** Choose what kind of round to score. */
export function RoundTypeScreen() {
  return (
    <>
      <Bar title="Round type" />
      <main class="form">
        {(['BIG_GAME', 'WOODSMAN'] as RoundType[]).map(t => (
          <button class="tile" onClick={() => go(`/new?type=${t}`)}>
            <span class="grow"><b>{ROUND_TYPE_NAMES[t]}</b><span class="sub">{ROUND_TYPE_HELP[t]}</span></span><span>›</span>
          </button>
        ))}
      </main>
    </>
  )
}
