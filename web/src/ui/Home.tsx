import { useState } from 'preact/hooks'
import { Bar, go, installed, isIos, toast, useData, ukDate } from './common'
import { getAccount } from '../data/account'
import { finished, inProgress, syncNow } from '../data/rounds'
import { getKv } from '../data/db'
import { classLine, roundTotal, targetsComplete, ROUND_TYPE_NAMES } from '../domain/round'

/** Home: account and sync, rounds in progress, and the main tiles. */
export function Home() {
  const account = useData(getAccount)
  const going = useData(inProgress) ?? []
  const done = useData(finished) ?? []
  const lastSync = useData(() => getKv<string>('lastSync'))
  const [syncing, setSyncing] = useState(false)
  const best = done.length ? Math.max(...done.filter(r => r.type === 'BIG_GAME').map(roundTotal), 0) : null

  async function sync() {
    setSyncing(true)
    const r = await syncNow()
    setSyncing(false)
    toast(r.ok ? 'Up to date.' : r.message ?? 'Couldn\'t sync.')
  }

  return (
    <>
      <Bar title="Field Archery Scoring" onBack={false}>
        <button class="icon" aria-label="Profile" onClick={() => go('/profile')}>👤</button>
      </Bar>
      <main>
        {!installed() && (
          <div class="banner">
            <b>Install the app</b>
            <span>{isIos()
              ? 'Tap Share (the square with an arrow) then "Add to Home Screen". It then works with no signal and keeps your rounds safe.'
              : 'Use your browser\'s menu → "Install app" or "Add to Home screen" so it works with no signal.'}</span>
          </div>
        )}
        <div class="card low row between">
          <div class="grow">
            {account ? <><b>{account.username}</b><div class="sub">{lastSync ? `Last synced ${new Date(lastSync).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}` : 'Not synced yet'}</div></>
              : <><b>Not signed in</b><div class="sub">Rounds stay on this phone. Sign in to back them up and use live groups.</div></>}
          </div>
          {account ? <button class="tonal" disabled={syncing} onClick={sync}>{syncing ? 'Syncing…' : 'Sync'}</button>
            : <button class="filled" onClick={() => go('/account')}>Sign in</button>}
        </div>

        <button class="tile hi" onClick={() => go('/new')}>
          <span class="dot">＋</span>
          <span><b>Score a round</b><span class="sub">Start a new round</span></span>
        </button>

        {going.map(r => (
          <button class="tile" onClick={() => go(`/score/${r.id}`)}>
            <span class="dot">🎯</span>
            <span class="grow"><b>Continue: {ROUND_TYPE_NAMES[r.type]}{r.groupId ? ' (group)' : ''}</b>
              <span class="sub">{ukDate(r.shootDate)} · {classLine(r)} · {targetsComplete(r)} of {r.targetCount} targets · {roundTotal(r)} points</span></span>
          </button>
        ))}

        <button class="tile" onClick={() => go('/history')}>
          <span class="dot">☰</span>
          <span><b>Score history</b><span class="sub">{done.length ? `${done.length} finished round${done.length === 1 ? '' : 's'}${best ? ` · best ${best}` : ''}` : 'No finished rounds yet'}</span></span>
        </button>

        <button class="tile" onClick={() => go('/join')}>
          <span class="dot">👥</span>
          <span><b>Join a live group</b><span class="sub">{account ? 'Type the code from the host\'s phone' : 'Sign in to join a live group'}</span></span>
        </button>

        <p class="sub" style="text-align:center">Web app 2.5 · stats, handicap, friends and shoot locations are in the Android app for now.</p>
      </main>
    </>
  )
}
