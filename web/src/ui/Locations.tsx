import { useEffect, useRef, useState } from 'preact/hooks'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { Bar, Card, Dialog, Field, Loading, back, errorText, go, one, toast, ukDate, useData, useLoad } from './common'
import { getAccount } from '../data/account'
import { getModel } from '../data/handicap'
import { getRound } from '../data/db'
import * as loc from '../data/locations'
import { BOW_STYLES, ROUND_TYPE_NAMES, locationStatus, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import { handicapPoints, type HandicapModel } from '../domain/handicap'

/** One line showing where the round is; tap for the location sheet. */
export function LocationChip({ round, signedIn, onClick }: { round: Round; signedIn: boolean; onClick: () => void }) {
  const st = locationStatus(round)
  const [text, action] = st === 'SET' ? [round.locationName ?? 'Location set', 'Not here?']
    : st === 'NEEDS_NAME' ? ['New location', 'Name it']
    : st === 'LOOKING' ? [signedIn ? 'Finding location… (needs signal)' : 'Position saved – sign in to find the location', null]
    : ['No location', signedIn ? 'Add' : null]
  return (
    <button class={`locchip ${st === 'NEEDS_NAME' ? 'hi' : ''}`} disabled={!signedIn} onClick={onClick}>
      <span>📍</span><span class="grow">{text}</span>{action && <b>{action}</b>}
    </button>
  )
}

/** Pick a nearby location ("Not here?"), name a new one at the round's position, or take a position now. */
export function LocationSheet({ roundId, open, onClose }: { roundId: string; open: boolean; onClose: () => void }) {
  const round = useData(() => getRound(roundId), [roundId])
  const [near, setNear] = useState<{ list?: loc.NearbyLocation[]; error?: string } | null>(null)
  const [known, setKnown] = useState<loc.MapLocation[]>([])
  const [name, setName] = useState('')
  const [priv, setPriv] = useState(false)
  const [busy, setBusy] = useState(false)
  const [taken, setTaken] = useState<loc.NearbyLocation | null>(null)
  const hasFix = round?.fixLat != null

  useEffect(() => {
    if (!open || !round) return
    setNear(null); setTaken(null)
    loc.mapLocations().then(setKnown, () => setKnown([]))
    if (hasFix) loc.nearby(round).then(list => setNear({ list }), e => setNear({ error: errorText(e) }))
  }, [open, hasFix])

  async function run(f: () => Promise<unknown>) {
    setBusy(true)
    try { await f() } catch (e) {
      if (e instanceof loc.NameTaken) setTaken(e.existing)
      else toast(errorText(e))
    } finally { setBusy(false) }
  }
  const choose = (id: string, n: string) => run(async () => { await loc.setLocation(roundId, id, n); onClose() })

  if (!round) return null
  const isSet = locationStatus(round) === 'SET'
  const others = near?.list?.filter(l => l.name !== round.locationName) ?? []
  return (
    <Dialog open={open} title={isSet ? 'Shoot location' : 'Where are you shooting?'} onClose={onClose} actions={<button onClick={onClose}>Close</button>}>
      <div class="col">
        {isSet && <div>Now: <b>{round.locationName}</b></div>}
        <button class="outlined" disabled={busy} onClick={() => { onClose(); go(`/map?pick=${roundId}`) }}>📍 Choose on map</button>
        {known.length > 0 && <label class="field">Known locations
          <select disabled={busy} value="" onChange={e => { const l = known.find(k => k.id === (e.target as HTMLSelectElement).value); if (l) choose(l.id, l.name) }}>
            <option value="">Pick a known location</option>
            {known.map(k => <option value={k.id}>{k.name}{k.isPrivate ? ' (private)' : ''}</option>)}
          </select></label>}
        {!hasFix ? <>
          <div class="sub">This round has no GPS position. If you're at the shoot now, get your position to find or name the location.</div>
          <button class="filled" disabled={busy} onClick={() => run(async () => { if (!(await loc.captureNow(roundId))) toast("Couldn't get your position. Check location is allowed for this site in Settings.") })}>
            {busy ? 'Getting position…' : 'Get position now'}</button>
        </> : <>
          {!near ? <div class="sub">Looking for nearby locations…</div>
            : near.error ? <div class="error">{near.error}</div>
            : others.length ? <><h3 class="section">Nearby</h3>
              {others.map(l => <button class="row between plain" disabled={busy} onClick={() => choose(l.id, l.name)}>
                <span>📍 {l.name}</span><span class="sub">{loc.distanceText(l.distanceMetres)}</span></button>)}</>
            : !isSet && <div class="sub">No shoot locations known nearby. You're the first to score here – give it a name.</div>}
          <h3 class="section">Name a new location</h3>
          <Field label="Club or ground name"><input maxLength={60} value={name} disabled={busy} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>
          {taken && <div class="banner">There's already a location called "{taken.name}".
            <button onClick={() => choose(taken.id, taken.name)} disabled={busy}>Use {taken.name}</button></div>}
          <PrivateSwitch value={priv} onChange={setPriv} />
          <button class="filled" disabled={busy || name.trim().length < 2} onClick={() => run(async () => {
            await loc.nameAt(roundId, name, round.fixLat!, round.fixLon!, priv); onClose()
          })}>Save new location</button>
          <div class="sub">Only your shoot location is shared, never your own position. Admins can correct names later.</div>
        </>}
      </div>
    </Dialog>
  )
}

function PrivateSwitch({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <label class="row" style="flex-wrap:nowrap;align-items:flex-start">
      <span class="grow"><b>Private</b><div class="sub">Only you see it, e.g. your garden. Others shooting here won't be matched to it. Admins only see it if it clashes with a public location.</div></span>
      <input type="checkbox" style="width:auto;margin-top:4px" checked={value} onChange={e => onChange((e.target as HTMLInputElement).checked)} />
    </label>
  )
}

/** Map of shoot locations (OpenStreetMap). Pick mode: choosing a location for a round. */
export function LocationMap({ pick }: { pick?: string }) {
  const account = useData(getAccount)
  const [data, retry] = useLoad(async () => (account ? loc.mapLocations() : null), [account?.token])
  const [placing, setPlacing] = useState(false)
  const [newAt, setNewAt] = useState<{ lat: number; lon: number } | null>(null)
  const [name, setName] = useState('')
  const [priv, setPriv] = useState(false)
  const [taken, setTaken] = useState<loc.NearbyLocation | null>(null)
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const tapRef = useRef<(lat: number, lon: number) => void>()
  tapRef.current = (lat, lon) => { if (pick || placing) { setNewAt({ lat, lon }); setName(''); setTaken(null) } }

  useEffect(() => {
    if (data.state !== 'ready' || !data.value || !box.current) return
    const list = data.value
    const map = L.map(box.current, { zoomControl: true })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap contributors' }).addTo(map)
    const style = getComputedStyle(document.documentElement)
    const shot = style.getPropertyValue('--primary').trim() || '#3a6a45'
    for (const l of list) {
      const m = L.circleMarker([l.latitude, l.longitude], { radius: 9, color: '#fff', weight: 2, fillColor: l.myRounds > 0 ? shot : '#8a8a8a', fillOpacity: 1 }).addTo(map)
      const div = document.createElement('div')
      const b = document.createElement('b'); b.textContent = l.name + (l.isPrivate ? ' (private)' : ''); div.append(b)
      const p = document.createElement('div')
      p.textContent = (l.myRounds > 0 ? `You: ${l.myRounds} round${l.myRounds === 1 ? '' : 's'}` : 'Not shot here yet') + (l.isPrivate ? '' : ` · everyone: ${l.rounds}`)
      div.append(p)
      const btn = document.createElement('button'); btn.className = 'tonal'; btn.style.marginTop = '6px'
      btn.textContent = pick ? 'Use this location' : 'Scorecards and stats'
      btn.onclick = async () => {
        if (pick) { await loc.setLocation(pick, l.id, l.name); back() } else go(`/location/${l.id}`)
      }
      div.append(btn)
      m.bindPopup(div)
    }
    if (list.length) map.fitBounds(L.latLngBounds(list.map(l => [l.latitude, l.longitude] as [number, number])).pad(0.2), { maxZoom: 13 })
    else map.setView([52.8, -1.5], 6)
    map.on('click', (e: L.LeafletMouseEvent) => tapRef.current?.(e.latlng.lat, e.latlng.lng))
    return () => { map.remove() }
  }, [data.state === 'ready' ? data.value : null])

  async function save() {
    if (!newAt) return
    setBusy(true)
    try {
      if (pick) { await loc.nameAt(pick, name, newAt.lat, newAt.lon, priv); setNewAt(null); back() }
      else { await loc.createLocation(name, newAt.lat, newAt.lon, priv); setNewAt(null); setPlacing(false); toast('Location added.'); retry() }
    } catch (e) {
      if (e instanceof loc.NameTaken) { if (pick) setTaken(e.existing); else toast(e.message) }
      else toast(errorText(e))
    } finally { setBusy(false) }
  }

  if (account === null) return <><Bar title="Shoot locations" /><main><Card title="Sign in to see shoot locations"><button class="filled" onClick={() => go('/profile')}>Go to Profile</button></Card></main></>
  const list = data.state === 'ready' ? data.value ?? [] : []
  return (
    <>
      <Bar title={pick ? 'Choose the shoot location' : 'Shoot locations'}>
        {!pick && !placing && data.state === 'ready' && <button class="tonal" onClick={() => setPlacing(true)}>＋ New</button>}
      </Bar>
      <main>
        {pick && <div class="banner">Tap a pin, then "Use this location". Or tap the map where the shoot is to name a new location.</div>}
        {placing && <div class="banner row between" style="flex-direction:row">Tap the map where the new location is.<button onClick={() => setPlacing(false)}>Cancel</button></div>}
        <Loading load={data} retry={retry}>{() => <>
          <div ref={box} class="map" />
          <div class="row sub"><span class="legend" style="background:var(--primary)" /> Shot here ({list.filter(l => l.myRounds > 0).length})
            <span class="legend" style="background:#8a8a8a" /> Not yet ({list.filter(l => !l.myRounds).length})</div>
        </>}</Loading>
      </main>
      <Dialog open={!!newAt} title="New shoot location" onClose={() => setNewAt(null)}
        actions={<><button onClick={() => setNewAt(null)}>Cancel</button><button disabled={busy || name.trim().length < 2} onClick={save}>Save</button></>}>
        <div class="col">
          <div>{pick ? "Name the location at the point you tapped. It's used for this round." : 'Name the location at the point you tapped.'}</div>
          <Field label="Club or ground name"><input maxLength={60} value={name} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>
          <PrivateSwitch value={priv} onChange={setPriv} />
          {taken && pick && <div class="error">There's already a location called "{taken.name}".
            <button onClick={async () => { await loc.setLocation(pick, taken.id, taken.name); setNewAt(null); back() }}>Use {taken.name}</button></div>}
        </div>
      </Dialog>
    </>
  )
}

const CLASS_ORDER = (c: { archerClass: ArcherClass; ageGroup: AgeGroup }) =>
  ['ADULT', 'JUNIOR', 'CUB'].indexOf(c.ageGroup) * 2 + (c.archerClass === 'LADIES' ? 1 : 0)

/** A location: your scorecards, friends' here, and everyone's stats by bow style (with a class filter). */
export function LocationPageScreen({ id }: { id: string }) {
  const model = useData(getModel)
  const [data, retry] = useLoad(() => loc.locationPage(id), [id])
  const [classKey, setClassKey] = useState('')
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const title = data.state === 'ready' ? data.value.location.name : 'Shoot location'
  return (
    <>
      <Bar title={title} />
      <main><Loading load={data} retry={retry}>{p => {
        const best = p.myRounds.length ? Math.max(...p.myRounds.map(r => r.totalPoints)) : null
        const classes = [...new Map(p.bowClasses.filter(b => b.archerClass && b.ageGroup)
          .map(b => [`${b.archerClass}-${b.ageGroup}`, { archerClass: b.archerClass!, ageGroup: b.ageGroup! }])).values()].sort((a, b) => CLASS_ORDER(a) - CLASS_ORDER(b))
        const chosen = classes.find(c => `${c.archerClass}-${c.ageGroup}` === classKey)
        const shown = chosen ? p.bowClasses.filter(b => b.archerClass === chosen.archerClass && b.ageGroup === chosen.ageGroup) : p.bowStyles
        const types = [...new Set(shown.map(b => b.roundType))]
        return <div class="split"><div>
          {p.location.isPrivate && <span class="chip" style="align-self:flex-start">🔒 Private: only you see it</span>}
          <h3 class="section">Your scorecards here</h3>
          {!p.myRounds.length ? <div class="sub">You haven't finished a round here yet.</div> : <>
            <div class="sub">{p.myRounds.length} round{p.myRounds.length === 1 ? '' : 's'}{best != null ? ` · best ${best}` : ''}</div>
            {p.myRounds.map(r => <SummaryRow r={r} onClick={() => go(`/card/${r.roundId}`)} />)}
          </>}
          {!p.location.isPrivate && <>
            <h3 class="section">Friends here</h3>
            {!p.friends.length && <div class="sub">None of your friends have finished a round here yet.</div>}
            {p.friends.map(f => {
              const k = f.archerId + f.roundType
              return (
                <div class="card low">
                  <button class="row between plain" onClick={() => setOpen({ ...open, [k]: !open[k] })}>
                    <span><b>{f.username}{f.roundType === 'WOODSMAN' ? ' · Woodsman' : ''}</b>
                      <div class="sub">{f.rounds} round{f.rounds === 1 ? '' : 's'}{f.bestTotal != null ? ` · best ${f.bestTotal}` : ''}
                        {f.averagePerAnimal != null ? ` · avg ${one(f.averagePerAnimal)} per ${f.roundType === 'WOODSMAN' ? 'target' : 'animal'}` : ''}
                        {f.average36 != null && f.average40 != null ? ` · avg 36 targets ${Math.trunc(f.average36)}, 40 targets ${Math.trunc(f.average40)}` : ''}</div></span>
                    <span>{open[k] ? '▲' : '▼'}</span>
                  </button>
                  {open[k] && f.roundsList.map(r => <SummaryRow r={r} onClick={() => go(`/friendcard/${f.archerId}/${r.roundId}?name=${encodeURIComponent(f.username)}`)} />)}
                </div>
              )
            })}
          </>}
          </div><div>
          {!p.location.isPrivate && <>
            <h3 class="section">All archers, by bow style</h3>
            {classes.length > 0 && <div class="filters"><select class={chosen ? 'on' : ''} value={classKey} onChange={e => setClassKey((e.target as HTMLSelectElement).value)}>
              <option value="">Class: All classes</option>
              {classes.map(c => <option value={`${c.archerClass}-${c.ageGroup}`}>{loc.classChoiceLabel(c.archerClass, c.ageGroup)}</option>)}
            </select></div>}
            {!shown.length ? <div class="sub">No finished rounds here yet.</div> : <>
              {types.map(t => <>
                {types.length > 1 && <b>{ROUND_TYPE_NAMES[t as 'BIG_GAME' | 'WOODSMAN'] ?? t}</b>}
                <BowStyleTable rows={shown.filter(b => b.roundType === t)} model={t === 'BIG_GAME' ? model ?? null : null} />
              </>)}
              <div class="sub">Everyone's finished rounds here. Big Game average per animal is out of 24 (predator/prey counts as 2, a fan as its animals);
                Woodsman is per target, out of 30. Avg 36 and Avg 40 are that average over a whole 36- or 40-target round, so rounds of any length compare fairly.
                {model ? ` Handicap is Avg 40 in handicap points (out of 1000, the same for the same performance with any bow; ${chosen ? `for ${loc.classChoiceLabel(chosen.archerClass, chosen.ageGroup)}` : 'each bow taken as Gents'}), so bow styles compare directly; All bows is their average, by rounds.` : ''}</div>
            </>}
          </>}
        </div></div>
      }}</Loading></main>
    </>
  )
}

function SummaryRow({ r, onClick }: { r: { shootDate: string; classLine: string; targetsLine: string; totalPoints: number }; onClick: () => void }) {
  return (
    <button class="tile" style="padding:12px 16px" onClick={onClick}>
      <span class="grow"><b style="font-size:16px">{ukDate(r.shootDate)}</b><span class="sub" style="display:block">{r.classLine} · {r.targetsLine}</span></span>
      <span class="num-big">{r.totalPoints}</span><span>›</span>
    </button>
  )
}

function BowStyleTable({ rows, model }: { rows: loc.BowStyleStats[]; model: HandicapModel | null }) {
  const hp = (b: loc.BowStyleStats) => model && b.average40 != null && BOW_STYLES.some(x => x.code === b.bowStyle)
    ? handicapPoints(model, b.average40, 960, { bowStyle: b.bowStyle, archerClass: b.archerClass ?? 'GENTS', ageGroup: b.ageGroup ?? 'ADULT' }, model.dataThroughYear) : null
  const withHp = rows.map(b => ({ v: hp(b), n: b.rounds })).filter((x): x is { v: number; n: number } => x.v != null)
  const f0 = (v: number | null) => (v == null ? '–' : Math.round(v).toString())
  return (
    <div class="card low scroll-x" style="padding:8px 0">
      <table class="equiv">
        <thead><tr><th>Bow</th><th>Archers</th><th>Rounds</th><th>Best</th><th>Avg 36</th><th>Avg 40</th><th>Per animal</th>{model && <th>Handicap</th>}</tr></thead>
        <tbody>
          {rows.map(b => (
            <tr><td>{BOW_STYLES.find(x => x.code === b.bowStyle)?.name ?? b.bowStyle}</td><td>{b.archers}</td><td>{b.rounds}</td><td>{b.bestTotal ?? '–'}</td>
              <td>{f0(b.average36)}</td><td>{f0(b.average40)}</td><td>{b.averagePerAnimal != null ? one(b.averagePerAnimal) : '–'}</td>{model && <td>{f0(hp(b))}</td>}</tr>
          ))}
          {model && withHp.length > 1 && <tr><td><b>All bows</b></td><td>{rows.reduce((s, b) => s + b.archers, 0)}</td><td>{rows.reduce((s, b) => s + b.rounds, 0)}</td>
            <td /><td /><td /><td /><td><b>{Math.round(withHp.reduce((s, x) => s + x.v * x.n, 0) / withHp.reduce((s, x) => s + x.n, 0))}</b></td></tr>}
        </tbody>
      </table>
    </div>
  )
}
