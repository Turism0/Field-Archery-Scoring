import type { ComponentChildren } from 'preact'
import { useState } from 'preact/hooks'
import { go, toast, useData } from './common'
import { getAccount, isAdmin } from '../data/account'
import { getKv } from '../data/db'
import { syncNow } from '../data/rounds'
import { normalised, TILE_TITLES, type HomeLayout, type TileId } from '../domain/homeLayout'
import { useOnline } from './Home'

const ROUTES: Record<TileId, { path: string; icon: string; match: string[] }> = {
  SCORE: { path: '/add', icon: '＋', match: ['add', 'new', 'grid', 'score', 'join', 'lobby', 'start', 'type'] },
  HISTORY: { path: '/history', icon: '☰', match: ['history', 'card', 'edit'] },
  STATS: { path: '/stats', icon: '★', match: ['stats'] },
  HANDICAP: { path: '/handicap', icon: 'ⓘ', match: ['handicap'] },
  FRIENDS: { path: '/friends', icon: '☺', match: ['friends', 'friend', 'friendcard', 'offer'] },
  LOCATIONS: { path: '/map', icon: '📍', match: ['map', 'location'] },
  ADMIN: { path: '/admin', icon: '⚙', match: ['admin'] },
}

/** The desktop layout: a sidebar (in the same order as Home's tiles, hidden ones left out) beside the page. */
export function Shell({ section, children }: { section: string; children: ComponentChildren }) {
  const account = useData(getAccount)
  const stored = useData(() => getKv<HomeLayout>('homeLayout'))
  const offers = useData(() => getKv<number>('offerCount')) ?? 0
  const duplicates = useData(() => getKv<number>('duplicateLocations')) ?? 0
  const lastSync = useData(() => getKv<string>('lastSync'))
  const [online, server] = useOnline()
  const [syncing, setSyncing] = useState(false)
  const layout = normalised(stored)
  const admin = isAdmin(account)
  // Desktop is for typing in paper rounds, not live scoring: no Join a live group (2.6.02).
  const items = layout.order.filter(id => (id !== 'ADMIN' || admin) && !layout.hidden.includes(id))
  const badge = (id: TileId) => (id === 'FRIENDS' && account ? offers : id === 'ADMIN' ? duplicates : 0)

  return (
    <div class="shell">
      <nav class="side" aria-label="Sections">
        <button class="brand" onClick={() => go('/')}><span class="logo">🎯</span><span>Field Archery<br />Scoring</span></button>
        <button class={`sideitem ${section === '' ? 'on' : ''}`} onClick={() => go('/')}><span class="si">⌂</span>Home</button>
        {items.map(id => {
          const r = ROUTES[id]
          return (
            <button class={`sideitem ${r.match.includes(section) ? 'on' : ''}`} onClick={() => go(r.path)}>
              <span class="si">{r.icon}</span><span class="grow">{id === 'SCORE' ? 'Add a round' : TILE_TITLES[id]}</span>
              {badge(id) > 0 && <span class="badge">{badge(id)}</span>}
            </button>
          )
        })}
        <div class="grow" />
        {!online ? <div class="sidenote">Device offline: you can still add rounds; they back up when you're online.</div>
          : server === 'UNAVAILABLE' && <div class="sidenote bad">Server unavailable: rounds back up when it's back.</div>}
        <div class="sideaccount">
          <button class={`sideitem ${['profile', 'account'].includes(section) ? 'on' : ''}`} onClick={() => go(account ? '/profile' : '/account')}>
            <span class="si">👤</span><span class="grow">{account ? account.username : 'Sign in'}
              {account && <span class="sub" style="display:block">{lastSync ? `Synced ${new Date(lastSync).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}` : 'Not synced yet'}</span>}</span>
          </button>
          {account && <button class="tonal" disabled={syncing} onClick={async () => {
            setSyncing(true); const r = await syncNow(); setSyncing(false); toast(r.ok ? 'Up to date.' : r.message ?? "Couldn't sync.")
          }}>{syncing ? 'Syncing…' : 'Sync now'}</button>}
          <div class="sub" style="text-align:center">Web app 2.7.01</div>
        </div>
      </nav>
      <div class="content">{children}</div>
    </div>
  )
}
