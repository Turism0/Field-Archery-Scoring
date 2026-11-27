import { useEffect, useRef, useState } from 'preact/hooks'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { Bar, Card, Dialog, Field, Loading, back, errorText, go, one, toast, ukDate, useData, useDesktop, useLoad } from './common'
import { getAccount } from '../data/account'
import { getModel } from '../data/handicap'
import { getRound } from '../data/db'
import * as loc from '../data/locations'
import { ApiError } from '../data/api'
import { BOW_STYLES, ROUND_TYPE_NAMES, locationStatus, type AgeGroup, type ArcherClass, type Round } from '../domain/round'
import { multiplier, type HandicapModel } from '../domain/handicap'
import { deleteLocationWarning, makePrivateWarning } from '../domain/locationPrivacy'
import { whyMatch, type PlaceMatch } from '../domain/locationMatch'

/** One line showing where the round is; tap for the location sheet. */
export function LocationChip({ round, signedIn, onClick }: { round: Round; signedIn: boolean; onClick: () => void }) {
  const st = locationStatus(round)
  const [text, action] = st === 'SET' ? [round.locationName ?? 'Location set', 'Not here?']
    : st === 'NEEDS_NAME' ? ['New location', 'Name it']
    // 3.2.25: named with no signal.
    : st === 'QUEUED' ? [`${round.pendingLocation!.name} (added when there's signal)`, 'Change']
    : st === 'CHECK' ? [`${round.pendingLocation!.name}: already known?`, 'Check']
    : st === 'LOOKING' ? [signedIn ? 'Finding location… (needs signal)' : 'Position saved – sign in to find the location', null]
    : ['No location', signedIn ? 'Add' : null]
  return (
    <button class={`locchip ${st === 'NEEDS_NAME' || st === 'CHECK' ? 'hi' : ''}`} disabled={!signedIn} onClick={onClick}>
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
  // 3.0: naming a location with no map pin (a shoot you can't place, e.g. a Nationals years ago).
  const [noPin, setNoPin] = useState(false)
  const [pinless, setPinless] = useState('')
  // 3.2.25: a new name that might be a location already known; and whether the lists are this device's copy (no signal).
  const [similar, setSimilar] = useState<{ matches: PlaceMatch[]; offline: boolean; name: string; noPin: boolean } | null>(null)
  const [offline, setOffline] = useState(false)
  const hasFix = round?.fixLat != null
  const desktop = useDesktop()

  useEffect(() => {
    if (!open || !round) return
    setNear(null); setTaken(null); setNoPin(false); setPinless(''); setSimilar(null); setOffline(false)
    loc.knownOrCached().then(k => { setKnown(k.list); if (k.cached) setOffline(true) }, () => setKnown([]))
    if (hasFix) loc.nearbyOrCached(round).then(n => { setNear({ list: n.list }); if (n.cached) setOffline(true) }, e => setNear({ error: errorText(e) }))
  }, [open, hasFix])

  async function run(f: () => Promise<unknown>, what?: { name: string; noPin: boolean }) {
    setBusy(true)
    try { await f() } catch (e) {
      if (e instanceof loc.NameTaken) setTaken(e.existing)
      else if (e instanceof loc.SimilarExists && what) setSimilar({ matches: e.matches, offline: e.offline, ...what })
      else toast(errorText(e))
    } finally { setBusy(false) }
  }
  /** "It's a new place": go ahead without asking again about the ones offered. */
  const anyway = () => {
    if (!similar || !round) return
    const skip = similar.matches.map(m => m.id)
    run(async () => {
      if (similar.noPin) await loc.nameOnly(roundId, similar.name, priv, false, skip)
      else await loc.nameAt(roundId, similar.name, round.fixLat!, round.fixLon!, priv, false, skip)
      setSimilar(null); onClose()
    })
  }
  const choose = (id: string, n: string) => run(async () => { await loc.setLocation(roundId, id, n); onClose() })

  if (!round) return null
  const st = locationStatus(round)
  const isSet = st === 'SET'
  const pending = round.pendingLocation
  const others = near?.list?.filter(l => l.name !== round.locationName) ?? []
  const similarPanel = (noPinPanel: boolean) => similar && similar.noPin === noPinPanel && (
    <div class="banner col" style="align-items:stretch">
      <b>Is "{similar.name}" one of these? They're already known.</b>
      {similar.matches.map(m => <MatchRow m={m} busy={busy} onUse={() => { setSimilar(null); choose(m.id, m.name) }} />)}
      <button class="outlined" disabled={busy} onClick={anyway}>{similar.offline ? "It's a new place (added when there's signal)" : "No, it's a new place"}</button>
    </div>)
  return (
    <Dialog open={open} title={isSet ? 'Shoot location' : 'Where are you shooting?'} onClose={onClose} actions={<button onClick={onClose}>Close</button>}>
      <div class="col">
        {isSet && <div>Now: <b>{round.locationName}</b></div>}
        {st === 'CHECK' && pending && <div class="banner col" style="align-items:stretch">
          <span>You named this "{pending.name}" with no signal. These are already known – is it one of them?</span>
          {pending.matches.map(m => <MatchRow m={m} busy={busy} onUse={() => run(async () => { await loc.settlePending(roundId, m); onClose() })} />)}
          <button class="outlined" disabled={busy} onClick={() => run(async () => { await loc.settlePending(roundId, null); onClose() })}>No, keep "{pending.name}"</button>
        </div>}
        {st === 'QUEUED' && pending && <div>"{pending.name}" is added as a new location when you have signal. To use a known location instead, pick it below.</div>}
        {offline && <div class="sub">No signal: using the locations saved on this device at your last sync.</div>}
        <button class="outlined" disabled={busy} onClick={() => { onClose(); go(`/map?pick=${roundId}`) }}>📍 Choose on map</button>
        {known.length > 0 && <label class="field">Known locations
          <select disabled={busy} value="" onChange={e => { const l = known.find(k => k.id === (e.target as HTMLSelectElement).value); if (l) choose(l.id, l.name) }}>
            <option value="">Pick a known location</option>
            {known.map(k => <option value={k.id}>{loc.knownLabel(k)}</option>)}
          </select></label>}
        {noPin ? <><NameOnlyForm name={pinless} setName={setPinless} priv={priv} setPriv={setPriv} busy={busy} taken={taken}
          onUse={t => choose(t.id, t.name)} onCancel={() => { setNoPin(false); setTaken(null); setSimilar(null) }}
          onSave={() => run(async () => { await loc.nameOnly(roundId, pinless, priv); onClose() }, { name: pinless.trim(), noPin: true })} />
          {similarPanel(true)}</>
        : <button class="outlined" disabled={busy} onClick={() => { setNoPin(true); setTaken(null) }}>Name only – no map pin</button>}
        {noPin ? null : !hasFix && desktop ? <div class="sub">Pick a known location, or choose on the map: tap a pin to use it, or tap where the shoot is to name a new location.</div>
        : !hasFix ? <>
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
          {similarPanel(false)}
          <PrivateSwitch value={priv} onChange={setPriv} />
          <button class="filled" disabled={busy || name.trim().length < 2} onClick={() => run(async () => {
            await loc.nameAt(roundId, name, round.fixLat!, round.fixLon!, priv); onClose()
          }, { name: name.trim(), noPin: false })}>Save new location</button>
          <div class="sub">Only your shoot location is shared, never your own position. Admins can correct names later.</div>
        </>}
      </div>
    </Dialog>
  )
}

/**
 * A new location with a name and no map pin (3.0), for a shoot whose place you don't know – a historical score, say.
 * It's on scorecards and has a page but isn't on the map; its creator or an Admin can put it on the map later.
 */
function NameOnlyForm({ name, setName, priv, setPriv, busy, taken, onUse, onSave, onCancel }: {
  name: string; setName: (s: string) => void; priv: boolean; setPriv: (b: boolean) => void; busy: boolean
  taken: loc.NearbyLocation | null; onUse: (t: loc.NearbyLocation) => void; onSave: () => void; onCancel: () => void
}) {
  return <>
    <h3 class="section">Name only – no map pin</h3>
    <div class="sub">For a shoot you can't put on the map, e.g. a Nationals years ago. If it's already known, pick it from Known locations above. The name shows on the scorecard; it isn't on the map until you put it there from its location page.</div>
    <Field label="Shoot name"><input maxLength={60} value={name} disabled={busy} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>
    {taken && <div class="banner">There's already a location called "{taken.name}".
      <button onClick={() => onUse(taken)} disabled={busy}>Use {taken.name}</button></div>}
    <PrivateSwitch value={priv} onChange={setPriv} />
    <div class="row"><button onClick={onCancel} disabled={busy}>Cancel</button>
      <button class="filled" disabled={busy || name.trim().length < 2} onClick={onSave}>Save name only</button></div>
  </>
}

/** A suggested location: its name, why it's suggested, and Use. */
function MatchRow({ m, busy, onUse }: { m: PlaceMatch; busy: boolean; onUse: () => void }) {
  return <div class="row between" style="flex-wrap:nowrap"><span>📍 <b>{m.name}</b><div class="sub">{whyMatch(m)}</div></span>
    <button class="tonal" disabled={busy} onClick={onUse}>Use</button></div>
}

function PrivateSwitch({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <label class="row" style="flex-wrap:nowrap;align-items:flex-start">
      <span class="grow"><b>Private</b><div class="sub">Other archers can't see it and it isn't on the map, e.g. your garden. Others shooting here won't be matched to it.</div></span>
      <input type="checkbox" style="width:auto;margin-top:4px" checked={value} onChange={e => onChange((e.target as HTMLInputElement).checked)} />
    </label>
  )
}

/**
 * Map of shoot locations (OpenStreetMap). Pick mode: choosing a location for a round. Place mode (3.0): putting a
 * name-only location on the map; with [move] (3.2.14, Admins) moving one that's already on it. Name-only locations are
 * listed under the map. 3.2.14: your own position shows as a blue dot (when the browser allows it), with a button to go to it.
 */
export function LocationMap({ pick, place, move = false }: { pick?: string; place?: string; move?: boolean }) {
  // null when signed out, undefined while it's still being read (2.7.05: getAccount gives undefined when signed out,
  // so the "Sign in" card never showed and a signed-out browser got an empty map box).
  const account = useData(async () => (await getAccount()) ?? null)
  const [data, retry] = useLoad(async () => (account ? loc.mapLocations() : null), [account?.token])
  const named = useData(async () => (account ? loc.namedLocations().catch(() => []) : []), [account?.token])
  const placing1 = place ? (move ? (data.state === 'ready' ? data.value?.find(l => l.id === place) : undefined) : named?.find(l => l.id === place)) : undefined
  const meRef = useRef<{ go: () => void } | null>(null)
  const [haveMe, setHaveMe] = useState(false)
  const [placing, setPlacing] = useState(false)
  const [newAt, setNewAt] = useState<{ lat: number; lon: number } | null>(null)
  const [name, setName] = useState('')
  const [priv, setPriv] = useState(false)
  const [taken, setTaken] = useState<loc.NearbyLocation | null>(null)
  const [similar, setSimilar] = useState<PlaceMatch[] | null>(null)   // 3.2.25
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const tapRef = useRef<(lat: number, lon: number) => void>()
  tapRef.current = (lat, lon) => { if (pick || placing || place) { setNewAt({ lat, lon }); setName(''); setTaken(null) } }

  useEffect(() => {
    if (data.state !== 'ready' || !data.value || !box.current) return
    // 3.2.31: others' private locations (Owner only) can't be picked for a round.
    const list = pick ? data.value.filter(l => !l.othersPrivate) : data.value
    const map = L.map(box.current, { zoomControl: true })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap contributors' }).addTo(map)
    const style = getComputedStyle(document.documentElement)
    const shot = style.getPropertyValue('--primary').trim() || '#3a6a45'
    for (const l of list) {
      const m = L.circleMarker([l.latitude, l.longitude], { radius: 9, color: '#fff', weight: 2, fillColor: l.othersPrivate ? OTHERS_PRIVATE : l.myRounds > 0 ? shot : '#8a8a8a', fillOpacity: 1 }).addTo(map)
      const div = document.createElement('div')
      const b = document.createElement('b'); b.textContent = l.name + (l.othersPrivate ? " (someone's private location)" : l.isPrivate ? ' (private)' : ''); div.append(b)
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
    // 3.2.14 (Pete): where you are, as a blue dot with how accurate it is. Only shown on this page; never sent anywhere.
    let me: L.CircleMarker | null = null, ring: L.Circle | null = null, watch: number | null = null
    if ('geolocation' in navigator) {
      watch = navigator.geolocation.watchPosition(p => {
        const at: [number, number] = [p.coords.latitude, p.coords.longitude]
        if (!me) {
          ring = L.circle(at, { radius: p.coords.accuracy, color: '#1a73e8', weight: 1, fillOpacity: 0.12, interactive: false }).addTo(map)
          me = L.circleMarker(at, { radius: 7, color: '#fff', weight: 2, fillColor: '#1a73e8', fillOpacity: 1, interactive: false }).addTo(map)
          setHaveMe(true)
        } else { me.setLatLng(at); ring!.setLatLng(at).setRadius(p.coords.accuracy) }
      }, () => { /* not allowed or no fix: no dot */ }, { enableHighAccuracy: true, maximumAge: 30_000 })
    }
    meRef.current = { go: () => { if (me) map.setView(me.getLatLng(), Math.max(map.getZoom(), 14)) } }
    return () => { if (watch != null) navigator.geolocation.clearWatch(watch); meRef.current = null; setHaveMe(false); map.remove() }
  }, [data.state === 'ready' ? data.value : null])

  /** check false (3.2.25): "It's a new place", not asking again about the ones offered. */
  async function save(check = true) {
    if (!newAt) return
    setBusy(true)
    const skip = check ? [] : (similar ?? []).map(m => m.id)
    try {
      if (place) { await loc.placeLocation(place, newAt.lat, newAt.lon, move); setNewAt(null); toast(`${placing1?.name ?? 'The location'} ${move ? 'has moved' : 'is on the map'}.`); back() }
      else if (pick) { await loc.nameAt(pick, name, newAt.lat, newAt.lon, priv, check, skip); setNewAt(null); setSimilar(null); back() }
      else { await loc.createLocation(name, newAt.lat, newAt.lon, priv, check, skip); setNewAt(null); setSimilar(null); setPlacing(false); toast('Location added.'); retry() }
    } catch (e) {
      if (e instanceof loc.NameTaken) { if (pick) setTaken(e.existing); else toast(e.message) }
      else if (e instanceof loc.SimilarExists) setSimilar(e.matches)
      else toast(errorText(e))
    } finally { setBusy(false) }
  }

  if (account === null) return <><Bar title="Shoot locations" /><main><Card title="Sign in to see shoot locations"><button class="filled" onClick={() => go('/profile')}>Go to Profile</button></Card></main></>
  const list = data.state === 'ready' ? data.value ?? [] : []
  return (
    <>
      <Bar title={place ? (move ? 'Move it on the map' : 'Put it on the map') : pick ? 'Choose the shoot location' : 'Shoot locations'}>
        {!pick && !place && !placing && data.state === 'ready' && <button class="tonal" onClick={() => setPlacing(true)}>＋ New</button>}
      </Bar>
      <main>
        {place && <div class="banner">Tap the map where {placing1?.name ?? 'the shoot'} {move ? 'really ' : ''}is.</div>}
        {pick && <div class="banner">Tap a pin, then "Use this location". Or tap the map where the shoot is to name a new location.</div>}
        {placing && <div class="banner row between" style="flex-direction:row">Tap the map where the new location is.<button onClick={() => setPlacing(false)}>Cancel</button></div>}
        {/* 3.2.33 (Pete): your private locations with a similar public one (the Locations badge). */}
        {!pick && !place && !placing && (() => {
          const toCheck = [...list, ...(named ?? [])].filter(l => l.checkSimilar)
          return toCheck.length > 0 && <div class="banner col" style="align-items:stretch">
            <span>{toCheck.length === 1 ? 'Your private location has a similar public one:' : `${toCheck.length} of your private locations have a similar public one:`}</span>
            {toCheck.map(l => <button class="row between plain" onClick={() => go(`/location/${l.id}`)}><b>{l.name}</b><span class="sub">Check ›</span></button>)}
          </div>
        })()}
        <Loading load={data} retry={retry}>{() => <>
          <div style="position:relative"><div ref={box} class="map" />
            {haveMe && <button class="tonal" style="position:absolute;right:10px;top:10px;z-index:500" onClick={() => meRef.current?.go()}>◎ Me</button>}</div>
          <div class="row sub"><span class="legend" style="background:var(--primary)" /> Shot here ({list.filter(l => l.myRounds > 0 && !l.othersPrivate).length})
            <span class="legend" style="background:#8a8a8a" /> Not yet ({list.filter(l => !l.myRounds && !l.othersPrivate).length})
            {!pick && list.some(l => l.othersPrivate) && <><span class="legend" style={`background:${OTHERS_PRIVATE}`} /> Others' private ({list.filter(l => l.othersPrivate).length})</>}</div>
          {!place && !!named?.length && <>
            <h3 class="section">No map pin ({named.length})</h3>
            <div class="sub">Shoots known by name only, e.g. championships from years ago.{pick ? ' Tap one to use it for this round.' : ''}</div>
            {named.map(l => <button class="row between plain" onClick={async () => {
              if (pick) { await loc.setLocation(pick, l.id, l.name); back() } else go(`/location/${l.id}`)
            }}><span>{l.name}{l.isPrivate ? ' (private)' : ''}</span>
              <span class="sub">{l.myRounds > 0 ? `You: ${l.myRounds}` : 'Not shot'}{l.isPrivate ? '' : ` · everyone: ${l.rounds}`}</span></button>)}
          </>}
        </>}</Loading>
      </main>
      <Dialog open={!!newAt && !!place} title={move ? 'Move it here?' : 'Put it on the map'} onClose={() => setNewAt(null)}
        actions={<><button onClick={() => setNewAt(null)}>Cancel</button><button disabled={busy} onClick={() => save()}>{move ? 'Move it here' : 'Put it here'}</button></>}>
        <div>{move
          ? <>Move <b>{placing1?.name ?? 'this location'}</b> to the point you tapped? Phones scoring there from now on are matched to the new place; rounds already there keep it.</>
          : <>Put <b>{placing1?.name ?? 'this location'}</b> at the point you tapped? From then on it's on the map and phones scoring there are matched to it. An Admin can move it later.</>}</div>
      </Dialog>
      <Dialog open={!!newAt && !place} title="New shoot location" onClose={() => { setNewAt(null); setSimilar(null) }}
        actions={<><button onClick={() => { setNewAt(null); setSimilar(null) }}>Cancel</button>
          {similar?.length ? <button disabled={busy || name.trim().length < 2} onClick={() => save(false)}>It's a new place</button>
            : <button disabled={busy || name.trim().length < 2} onClick={() => save()}>Save</button>}</>}>
        <div class="col">
          <div>{pick ? "Name the location at the point you tapped. It's used for this round." : 'Name the location at the point you tapped.'}</div>
          <Field label="Club or ground name"><input maxLength={60} value={name} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>
          <PrivateSwitch value={priv} onChange={setPriv} />
          {taken && pick && <div class="error">There's already a location called "{taken.name}".
            <button onClick={async () => { await loc.setLocation(pick, taken.id, taken.name); setNewAt(null); back() }}>Use {taken.name}</button></div>}
          {!!similar?.length && <div class="banner col" style="align-items:stretch">
            <b>{pick ? 'Is it one of these? They\'re already known.' : 'These are already known. Is it one of them?'}</b>
            {similar.map(m => pick ? <MatchRow m={m} busy={busy} onUse={async () => { await loc.setLocation(pick, m.id, m.name); setNewAt(null); setSimilar(null); back() }} />
              : <div><b>{m.name}</b><div class="sub">{whyMatch(m)}</div></div>)}
          </div>}
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
          {p.location.canChangePrivacy ? <PrivacyCard page={p} id={id} onChanged={retry} />
            : p.location.isPrivate && <span class="chip" style="align-self:flex-start">🔒 {p.location.othersPrivate ? "Someone else's private location: other archers can't see it" : "Private: other archers can't see it"}</span>}
          {!loc.hasPin(p.location) && <Card title="No map pin" sub="This shoot is known by its name only, so it isn't on the map.">
            {p.location.canPlace && <button class="tonal" style="align-self:flex-start" onClick={() => go(`/map?place=${p.location.id}`)}>📍 Put it on the map</button>}
          </Card>}
          {p.location.canMove && <button class="outlined" style="align-self:flex-start" onClick={() => go(`/map?place=${p.location.id}&move=1`)}>📍 Move it on the map (Admin)</button>}
          {p.location.canDelete && <DeleteLocation page={p} />}
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
                {model ? ` × multiplier is Avg 40 times the class multiplier for each bow (${chosen ? `for ${loc.classChoiceLabel(chosen.archerClass, chosen.ageGroup)}` : 'each bow taken as Gents'}), as in a handicap competition, so bow styles compare directly; All bows is their average, by rounds.` : ''}</div>
            </>}
          </>}
        </div></div>
      }}</Loading></main>
    </>
  )
}

/** Private or public, for the archer who named the location (3.2.12): warns first when others have shot there. */
function PrivacyCard({ page, id, onChanged }: { page: loc.LocationPage; id: string; onChanged: () => void }) {
  const l = page.location
  const [busy, setBusy] = useState(false)
  const [ask, setAsk] = useState<string | null>(null)
  const change = async (isPrivate: boolean, confirm = false) => {
    setBusy(true)
    try {
      await loc.setPrivate(id, isPrivate, confirm)
      setAsk(null)
      toast(isPrivate ? 'It\'s private now.' : 'It\'s public now.')
      onChanged()
    } catch (e) {
      // Someone shot there since the page loaded: ask with the server's numbers.
      if (e instanceof ApiError && e.code === 'others_shot_here') setAsk(makePrivateWarning(l.name, e.body?.archers ?? 1, e.body?.rounds ?? 1))
      else toast(errorText(e))
    } finally { setBusy(false) }
  }
  // 3.2.32 (Pete): merging your private location into a similar public one.
  const [targets, setTargets] = useState<PlaceMatch[] | null>(null)
  const [into, setInto] = useState<PlaceMatch | null>(null)
  const startMerge = async () => {
    setBusy(true)
    try { setTargets(await loc.mergeTargets(id)) } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  const merge = async () => {
    if (!into) return
    setBusy(true)
    try {
      const name = await loc.mergeInto(id, into.id)
      toast(`Merged into ${name || into.name}. Your rounds there moved with it.`)
      setInto(null); setTargets(null)
      onChanged()   // the page now shows the public location (a merged id leads there)
    } catch (e) { setInto(null); setTargets(null); toast(errorText(e)) } finally { setBusy(false) }
  }
  const toggle = (v: boolean) => {
    const warning = v ? makePrivateWarning(l.name, l.otherArchers, l.otherRounds) : null
    if (warning) setAsk(warning)
    else change(v)
  }
  // 3.2.33 (Pete): a similar public location exists: merge into it, or say it's a different place.
  const notSame = async () => {
    setBusy(true)
    try {
      await loc.notSame(id, l.similarPublic.map(m => m.id))
      toast(l.similarPublic.length === 1 ? `OK, ${l.similarPublic[0].name} won't be suggested again.` : "OK, they won't be suggested again.")
      onChanged()
    } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  return (
    <div class="card low">
      {l.similarPublic.length > 0 && <div class="banner col" style="align-items:stretch">
        <b>{l.similarPublic.length === 1 ? "There's a similar public location" : 'There are similar public locations'}</b>
        <div class="sub">If your private location is really {l.similarPublic.length === 1 ? 'it' : 'one of them'}, merge it in: your rounds here move there. If not, say it's a different place and it won't be suggested again.</div>
        {l.similarPublic.map(m => <div class="row between" style="flex-wrap:nowrap">
          <span><b>{m.name}</b><div class="sub">{whyMatch(m)}</div></span>
          <button class="tonal" disabled={busy} onClick={() => setInto(m)}>Merge</button></div>)}
        <button style="align-self:flex-end" disabled={busy} onClick={notSame}>{l.similarPublic.length === 1 ? "It's a different place" : "They're different places"}</button>
      </div>}
      <label class="row" style="flex-wrap:nowrap;align-items:flex-start">
        <span class="grow"><b>🔒 Private</b><div class="sub">{l.isPrivate
          ? 'Other archers can\'t see it. Turn off to share it: it goes on the map and other archers can be matched to it.'
          : 'You named this location, so you can make it private: other archers won\'t see it, e.g. your garden.'}</div></span>
        <input type="checkbox" style="width:auto;margin-top:4px" checked={l.isPrivate} disabled={busy}
          onChange={e => { const v = (e.target as HTMLInputElement).checked; (e.target as HTMLInputElement).checked = l.isPrivate; toggle(v) }} />
      </label>
      {l.canMergeIntoPublic && <button class="outlined" style="align-self:flex-start" disabled={busy} onClick={startMerge}>Merge into a public location</button>}
      <Dialog open={!!targets && !into} title="Merge into a public location" onClose={() => setTargets(null)}
        actions={<button onClick={() => setTargets(null)}>Cancel</button>}>
        {!targets?.length ? <p>There's no public location with the same or a similar name, or nearby, to merge it into.</p> : <>
          <p>Public locations like this one. Pick the one it really is:</p>
          {targets.map(m => <button class="row between plain" onClick={() => setInto(m)}>
            <span><b>{m.name}</b><div class="sub">{whyMatch(m)}</div></span></button>)}
        </>}
      </Dialog>
      <Dialog open={!!into} title={`Merge into ${into?.name ?? ''}?`} onClose={() => setInto(null)}
        actions={<><button disabled={busy} onClick={() => setInto(null)}>Cancel</button>
          <button class="tonal" disabled={busy} onClick={merge}>Merge</button></>}>
        <p>Your private location goes, and your rounds there move to {into?.name} (a public location, on the map). It can't be undone.</p>
      </Dialog>
      <Dialog open={!!ask} title="Make it private?" onClose={() => setAsk(null)}
        actions={<><button disabled={busy} onClick={() => setAsk(null)}>Cancel</button>
          <button class="danger" disabled={busy} onClick={() => change(true, true)}>Make private</button></>}>
        <p>{ask}</p>
      </Dialog>
    </div>
  )
}

/** Delete this location (3.2.16): the archer who named it, or the Owner. Asks first, then goes back. */
function DeleteLocation({ page }: { page: loc.LocationPage }) {
  const [ask, setAsk] = useState(false)
  const [busy, setBusy] = useState(false)
  const del = async () => {
    setBusy(true)
    try { await loc.deleteLocation(page.location.id); setAsk(false); toast(`${page.location.name} deleted.`); back() }
    catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  return <>
    <button class="danger" style="align-self:flex-start" onClick={() => setAsk(true)}>🗑 Delete location</button>
    <Dialog open={ask} title="Delete this location?" onClose={() => setAsk(false)}
      actions={<><button disabled={busy} onClick={() => setAsk(false)}>Cancel</button><button class="danger" disabled={busy} onClick={del}>Delete</button></>}>
      <p>{deleteLocationWarning(page.location.name, page.location.roundsHere)}</p>
    </Dialog>
  </>
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
  // 2.9: Avg 40 × the class multiplier (was handicap points out of 1000).
  const hp = (b: loc.BowStyleStats) => {
    const m = model && b.average40 != null && BOW_STYLES.some(x => x.code === b.bowStyle)
      ? multiplier(model, { bowStyle: b.bowStyle, archerClass: b.archerClass ?? 'GENTS', ageGroup: b.ageGroup ?? 'ADULT' }) : null
    return m != null ? b.average40! * m : null
  }
  const withHp = rows.map(b => ({ v: hp(b), n: b.rounds })).filter((x): x is { v: number; n: number } => x.v != null)
  const f0 = (v: number | null) => (v == null ? '–' : Math.round(v).toString())
  return (
    <div class="card low scroll-x" style="padding:8px 0">
      <table class="equiv">
        <thead><tr><th>Bow</th><th>Archers</th><th>Rounds</th><th>Best</th><th>Avg 36</th><th>Avg 40</th><th>Per animal</th>{model && <th>× multiplier</th>}</tr></thead>
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

/** 3.2.31: other archers' private locations on the Owner's map (purple, unlike the green / grey of the others). */
const OTHERS_PRIVATE = '#7b4fa0'
