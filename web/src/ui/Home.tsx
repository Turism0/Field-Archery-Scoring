import { useEffect, useRef, useState } from 'preact/hooks'
import { APP_VERSION } from '../version'
import { Bar, go, installed, isIos, toast, useData } from './common'
import { getAccount, isAdmin } from '../data/account'
import { finished, inProgress, syncNow } from '../data/rounds'
import { getKv, setKv } from '../data/db'
import { getServerState, onServerState, type ServerState } from '../data/api'
import { statsTotal } from '../domain/round'
import { handicapEntry, personalHandicaps } from '../domain/handicap'
import {
  DEFAULT_LAYOUT, TILE_TITLES, canHide, isDefault, move, normalised, setHidden, type HomeLayout, type TileId,
} from '../domain/homeLayout'

export function useOnline(): [boolean, ServerState] {
  const [online, setOnline] = useState(navigator.onLine)
  const [server, setServer] = useState(getServerState())
  useEffect(() => {
    const on = () => setOnline(navigator.onLine)
    addEventListener('online', on); addEventListener('offline', on)
    const off = onServerState(setServer)
    return () => { removeEventListener('online', on); removeEventListener('offline', on); off() }
  }, [])
  return [online, server]
}

/** Home: connection, account and sync, and the tiles (arrangeable). */
export function Home() {
  const account = useData(getAccount)
  const going = useData(inProgress) ?? []
  const done = useData(finished)
  const lastSync = useData(() => getKv<string>('lastSync'))
  const offers = useData(() => getKv<number>('offerCount')) ?? 0
  const duplicates = useData(() => getKv<number>('duplicateLocations')) ?? 0
  const stored = useData(() => getKv<HomeLayout>('homeLayout'))
  const [online, server] = useOnline()
  const [syncing, setSyncing] = useState(false)
  const [editing, setEditing] = useState<HomeLayout | null>(null)
  const layout = normalised(stored)
  const signedIn = !!account
  const admin = signedIn && isAdmin(account)
  const shown = layout.order.filter(id => id !== 'ADMIN' || admin)
  const bestBig = done?.length ? Math.max(0, ...done.filter(r => r.type === 'BIG_GAME').map(statsTotal)) : null
  // Personal handicaps (2.9), your main bow's first.
  const hs = done ? personalHandicaps(done.map(handicapEntry).filter((e): e is NonNullable<typeof e> => !!e)) : []

  async function sync() {
    setSyncing(true)
    const r = await syncNow()
    setSyncing(false)
    toast(r.ok ? 'Up to date.' : r.message ?? 'Couldn\'t sync.')
  }

  const tile = (id: TileId) => {
    switch (id) {
      case 'SCORE': return <Tile hi icon="＋" title="Score a round" onClick={() => go('/start')}
        sub={going.length === 1 ? '1 round in progress · continue or start new' : going.length > 1 ? `${going.length} rounds in progress · continue or start new` : 'Start a new round'} />
      case 'HISTORY': return <Tile icon="☰" title="Score history" sub={!done ? ' ' : done.length ? `${done.length} finished round${done.length === 1 ? '' : 's'}${bestBig ? ` · best ${bestBig}` : ''}` : 'No finished rounds yet'} onClick={() => go('/history')} />
      case 'STATS': return <Tile icon="★" title="Stats" sub={done?.length === 0 ? 'Personal bests, trends and seasons once you finish a round' : 'Personal bests, trends and season summaries'} onClick={() => go('/stats')} />
      case 'HANDICAP': return <Tile icon="ⓘ" title="Handicaps" onClick={() => go('/handicap')}
        sub={hs.length ? `Your handicap: ${hs.map(h => `${h.bowStyle} ${h.handicap}`).join(' · ')} · class multipliers` : 'Your personal handicap, and the class multipliers for handicap competitions'} />
      case 'FRIENDS': return <Tile icon="☺" title="Friends" badge={signedIn ? offers : 0}
        sub={!signedIn ? "Sign in to see friends' scorecards" : offers > 0 ? `${offers} round${offers === 1 ? '' : 's'} scored for you to accept` : "Friends' scorecards and stats, requests"} onClick={() => go('/friends')} />
      case 'LOCATIONS': return <Tile icon="📍" title="Shoot locations" sub={signedIn ? 'Map of locations, your scorecards and stats at each' : 'Sign in to see the map of shoot locations'} onClick={() => go('/map')} />
      case 'ADMIN': return <Tile icon="⚙" title="Admin" badge={duplicates}
        sub={duplicates > 0 ? `${duplicates} possible duplicate location${duplicates === 1 ? '' : 's'} to check` : account?.role === 'OWNER' ? 'Accounts, admins and shoot locations' : 'Accounts and shoot locations'} onClick={() => go('/admin')} />
    }
  }

  return (
    <>
      <Bar title="Field Archery Scoring" onBack={false}>
        {editing ? <button onClick={async () => { await setKv('homeLayout', normalised(editing)); setEditing(null) }}>Done</button>
          : <button class="icon" aria-label="Profile" onClick={() => go('/profile')}>👤</button>}
      </Bar>
      <main>
        {!installed() && !editing && (
          <div class="banner">
            <b>Install the app</b>
            <span>{isIos()
              ? 'Tap Share (the square with an arrow) then "Add to Home Screen". It then works with no signal and keeps your rounds safe.'
              : 'Use your browser\'s menu → "Install app" or "Add to Home screen" so it works with no signal.'}</span>
          </div>
        )}
        {!online ? <div class="banner"><b>Device offline</b><span>Scoring works as normal. Rounds back up when you're online again.</span></div>
          : server === 'UNAVAILABLE' && <div class="banner bad"><b>Server unavailable</b><span>Scoring works as normal. Rounds back up when the server is back.</span></div>}
        <div class="card low row between">
          <button class="grow plain" style="padding:0" onClick={() => go('/profile')}>
            {account ? <><b>Signed in as {account.username}{admin ? ` · ${account.role === 'OWNER' ? 'Owner' : 'Admin'}` : ''}</b>
              <div class="sub">{syncText(lastSync)}</div></>
              : <><b>Not signed in</b><div class="sub">Scoring works offline. Sign in (Profile) to back up your rounds and use Friends.</div></>}
          </button>
          {account ? <button class="tonal" disabled={syncing} onClick={sync}>{syncing ? 'Syncing…' : 'Sync'}</button>
            : <button class="filled" onClick={() => go('/account')}>Sign in</button>}
        </div>

        {editing ? <Arrange layout={editing} shown={shown} onChange={setEditing} />
          : <>
            {shown.filter(id => !layout.hidden.includes(id)).map(tile)}
            <button style="align-self:center" onClick={() => setEditing(layout)}>⇅ Arrange tiles</button>
            <p class="sub" style="text-align:center">Web app {APP_VERSION}</p>
          </>}
      </main>
    </>
  )
}

function Tile({ icon, title, sub, onClick, hi, badge = 0 }: { icon: string; title: string; sub: string; onClick: () => void; hi?: boolean; badge?: number }) {
  return (
    <button class={`tile ${hi ? 'hi' : ''}`} onClick={onClick}>
      <span class="dot">{icon}</span>
      <span class="grow"><b>{title}</b><span class="sub">{sub}</span></span>
      {badge > 0 && <span class="badge">{badge}</span>}
    </button>
  )
}

/** Arranging Home: drag by ≡ or use the arrows; untick to hide (Score a round always shows). */
export function Arrange({ layout, shown, onChange }: { layout: HomeLayout; shown: TileId[]; onChange: (l: HomeLayout) => void }) {
  const ids = layout.order.filter(id => shown.includes(id))
  const [drag, setDrag] = useState<{ id: TileId; dy: number } | null>(null)
  const latest = useRef({ layout, ids }); latest.current = { layout, ids }
  const rows = useRef<Record<string, HTMLDivElement | null>>({})

  function start(id: TileId, e: PointerEvent) {
    e.preventDefault()
    let y = e.clientY, dy = 0
    setDrag({ id, dy: 0 })
    const moveH = (ev: PointerEvent) => {
      dy += ev.clientY - y; y = ev.clientY
      const { layout: l, ids: list } = latest.current
      const i = list.indexOf(id)
      const h = (k: TileId | undefined) => (k ? (rows.current[k]?.offsetHeight ?? 60) + 8 : 0)
      let nl = l
      if (dy > 0 && i < list.length - 1 && dy > h(list[i + 1]) / 2) { const hh = h(list[i + 1]); nl = move(l, id, 1, shown); dy -= hh }
      else if (dy < 0 && i > 0 && -dy > h(list[i - 1]) / 2) { const hh = h(list[i - 1]); nl = move(l, id, -1, shown); dy += hh }
      if (nl !== l) onChange(nl)
      setDrag({ id, dy })
    }
    const end = () => { setDrag(null); removeEventListener('pointermove', moveH); removeEventListener('pointerup', end); removeEventListener('pointercancel', end) }
    addEventListener('pointermove', moveH); addEventListener('pointerup', end); addEventListener('pointercancel', end)
  }

  return <>
    <div class="sub">Drag a tile by ≡ or use the arrows. Untick a tile to hide it. Tap Done at the top when you're finished.</div>
    {ids.map((id, i) => {
      const hidden = layout.hidden.includes(id)
      const d = drag?.id === id
      return (
        <div ref={el => { rows.current[id] = el }} class="card tile-edit" style={d ? `transform:translateY(${drag!.dy}px);z-index:2;position:relative;background:var(--secondary-c)` : ''}>
          <span class="handle" aria-label={`Drag to move ${TILE_TITLES[id]}`} onPointerDown={e => start(id, e as unknown as PointerEvent)}>≡</span>
          <span class="grow">{TILE_TITLES[id]}{hidden ? ' (hidden)' : ''}</span>
          <button class="icon" aria-label={`Move ${TILE_TITLES[id]} up`} disabled={i === 0} onClick={() => onChange(move(layout, id, -1, shown))}>▲</button>
          <button class="icon" aria-label={`Move ${TILE_TITLES[id]} down`} disabled={i === ids.length - 1} onClick={() => onChange(move(layout, id, 1, shown))}>▼</button>
          <input type="checkbox" aria-label={`Show ${TILE_TITLES[id]}`} style="width:auto" checked={!hidden} disabled={!canHide(id)} onChange={e => onChange(setHidden(layout, id, !(e.target as HTMLInputElement).checked))} />
        </div>
      )
    })}
    {!isDefault(layout) && <button style="align-self:center" onClick={() => onChange(DEFAULT_LAYOUT)}>Back to the usual order</button>}
  </>
}

/** "Last synced 21:45" (time only for today, date and time otherwise), as the app. */
export function syncText(lastSync: string | null | undefined): string {
  if (!lastSync) return 'Not synced yet'
  const d = new Date(lastSync)
  const today = d.toDateString() === new Date().toDateString()
  const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
  return `Last synced ${today ? time : `${d.getDate()} ${d.toLocaleDateString('en-GB', { month: 'short' })} ${time}`}`
}
