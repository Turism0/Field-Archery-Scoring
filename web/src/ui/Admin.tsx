import { useEffect, useState } from 'preact/hooks'
import { Bar, Card, Dialog, Field, Loading, errorText, go, toast, ukDate, useData, useLoad } from './common'
import { getAccount, isAdmin } from '../data/account'
import { getKv } from '../data/db'
import * as admin from '../data/admin'
import { activateHandicapModel, adminHandicap, requestHandicapFetch, setHandicapTargets } from '../data/handicap'
import { distanceText } from '../data/locations'

const ROLE_NAMES: Record<string, string> = { OWNER: 'Owner', ADMIN: 'Admin', USER: 'User' }
const dt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '')

function useAdminGuard() {
  const account = useData(getAccount)
  // Lost admin (the server said no): leave.
  useEffect(() => { if (account !== undefined && !isAdmin(account)) go('/') }, [account])
  return account
}

/** Admin: possible duplicates, shoot locations, handicap, and accounts (Owner makes admins). */
export function Admin() {
  const account = useAdminGuard()
  const duplicates = useData(() => getKv<number>('duplicateLocations')) ?? 0
  const [list, retry] = useLoad(() => admin.archers(), [])
  const [busy, setBusy] = useState(false)
  const [confirmReset, setConfirmReset] = useState<admin.AdminArcher | null>(null)
  const [code, setCode] = useState<{ username: string; code: string; expiresUtc: string | null } | null>(null)
  const owner = account?.role === 'OWNER'
  useEffect(() => { admin.duplicates().catch(() => {}) }, [])

  async function act(f: () => Promise<unknown>, done?: string) {
    setBusy(true)
    try { await f(); if (done) toast(done); retry() } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }

  return (
    <>
      <Bar title="Admin" />
      <main>
        <button class={`tile ${duplicates > 0 ? 'warn' : ''}`} onClick={() => go('/admin/duplicates')}>
          <span class="dot">⚠</span><span class="grow"><b>Possible duplicates</b>
            <span class="sub">{duplicates > 0 ? `${duplicates} to check: merge or mark as different places` : 'Nothing to check'}</span></span><span>›</span></button>
        <button class="tile" onClick={() => go('/admin/locations')}>
          <span class="dot">📍</span><span class="grow"><b>Shoot locations</b><span class="sub">Rename, make private or public, merge</span></span><span>›</span></button>
        <button class="tile" onClick={() => go('/admin/handicap')}>
          <span class="dot">ⓘ</span><span class="grow"><b>Handicap</b><span class="sub">NFAS results, targets per day, models</span></span><span>›</span></button>
        <Card title="Accounts" sub={owner ? 'Switch Admin on to let someone rename and merge shoot locations. Only you can make or remove admins.' : 'Only the owner can make or remove admins.'}>
          <Loading load={list} retry={retry}>{as => <>
            <div class="sub">{as.length} account{as.length === 1 ? '' : 's'}</div>
            {as.map(a => (
              <div class="card low">
                <div class="row between" style="flex-wrap:nowrap">
                  <span class="grow"><b>{a.username}</b> <span class="sub">{ROLE_NAMES[a.role] ?? a.role}</span>
                    <div class="sub">{a.createdUtc ? `Joined ${ukDate(a.createdUtc)}` : ''}{a.lastLoginUtc ? ` · last signed in ${ukDate(a.lastLoginUtc)}` : ''}</div></span>
                  {owner && a.role !== 'OWNER' && <label class="row" style="flex-wrap:nowrap">Admin
                    <input type="checkbox" style="width:auto" disabled={busy} checked={a.role === 'ADMIN'}
                      onChange={e => act(() => admin.setAdmin(a.archerId, (e.target as HTMLInputElement).checked))} /></label>}
                </div>
                {a.role !== 'OWNER' && a.archerId !== account?.archerId && <button class="outlined" style="align-self:flex-start" disabled={busy} onClick={() => setConfirmReset(a)}>Password reset code</button>}
              </div>
            ))}
          </>}</Loading>
        </Card>
      </main>
      <Dialog open={!!confirmReset} title={`Reset code for ${confirmReset?.username}?`} onClose={() => setConfirmReset(null)}
        actions={<><button onClick={() => setConfirmReset(null)}>Cancel</button>
          <button onClick={() => { const a = confirmReset!; setConfirmReset(null); act(async () => setCode(await admin.resetCode(a.archerId))) }}>Make code</button></>}>
        Only for someone who has lost both their password and their recovery code. Check it's really {confirmReset?.username} first (e.g. in person):
        the code lets whoever has it set a new password. It works once, for 24 hours.
      </Dialog>
      <Dialog open={!!code} title={`Reset code for ${code?.username}`} onClose={() => setCode(null)} actions={<button onClick={() => setCode(null)}>Done</button>}>
        <p class="code" style="user-select:all">{code?.code}</p>
        <p>Give it to {code?.username} (tell them, don't post it). On the sign-in screen they enter their username, tap Forgot your password?, and use this code.
          Works once{code?.expiresUtc ? `, until ${new Date(code.expiresUtc).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}` : ''}. Making another replaces it.</p>
      </Dialog>
    </>
  )
}

const REASONS: Record<string, string> = { nearby: 'Nearby', same_name: 'Same name', similar_name: 'Similar name' }

export function AdminDuplicates() {
  useAdminGuard()
  const [data, retry] = useLoad(() => admin.duplicates(), [])
  const [busy, setBusy] = useState(false)
  const [merge, setMerge] = useState<[admin.AdminLocation, admin.AdminLocation] | null>(null)
  async function act(f: () => Promise<unknown>, done?: string) {
    setBusy(true)
    try { await f(); if (done) toast(done); retry() } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  const locLine = (l: admin.AdminLocation) => `${l.rounds} round${l.rounds === 1 ? '' : 's'}${l.createdBy ? ` · named by ${l.createdBy}` : ''}`
  return (
    <>
      <Bar title="Possible duplicates" />
      <main>
        <Card title="Check these" sub={'Locations within a quarter mile of each other, or with the same or a similar name. Merge the duplicate into the one to keep, or tap "Different places" so they stop showing.'} />
        <Loading load={data} retry={retry}>{pairs => <>
          {!pairs.length && <p class="sub">Nothing to check. All locations look distinct.</p>}
          {pairs.map(p => (
            <div class="card low">
              <div class="sub">{p.reasons.map(r => REASONS[r] ?? r).join(' · ')} · {p.distanceMetres < 1000 ? `${p.distanceMetres} m` : `${(p.distanceMetres / 1000).toFixed(1)} km`} apart</div>
              {[p.a, p.b].map((l, i) => <div><b>{i + 1}. {l.name}</b>{l.isPrivate && <span class="sub"> · PRIVATE</span>}<div class="sub">{locLine(l)}</div></div>)}
              <div class="row">
                <button class="outlined" disabled={busy || p.b.isPrivate} onClick={() => setMerge([p.a, p.b])}>Keep 2nd</button>
                <button class="outlined" disabled={busy || p.a.isPrivate} onClick={() => setMerge([p.b, p.a])}>Keep 1st</button>
              </div>
              {(p.a.isPrivate || p.b.isPrivate) && <div class="sub">Private locations are only shown to Admins when they clash with a public one. Keep the public one to merge the private one into it, or mark them as different places.</div>}
              <button disabled={busy} onClick={() => act(() => admin.approve(p), 'Marked as different places.')}>Different places – stop flagging</button>
            </div>
          ))}
        </>}</Loading>
      </main>
      <MergeDialog pair={merge} onClose={() => setMerge(null)} onMerge={(dup, keep) => act(async () => toast(`Merged: ${await admin.merge(dup.id, keep.id)} rounds moved.`))} />
    </>
  )
}

function MergeDialog({ pair, onClose, onMerge }: { pair: [admin.AdminLocation, admin.AdminLocation] | null; onClose: () => void; onMerge: (dup: admin.AdminLocation, keep: admin.AdminLocation) => void }) {
  const [dup, keep] = pair ?? []
  return (
    <Dialog open={!!pair} title="Merge locations?" onClose={onClose}
      actions={<><button onClick={onClose}>Cancel</button><button class="danger" onClick={() => { onClose(); onMerge(dup!, keep!) }}>Merge</button></>}>
      {dup && keep && <>{dup.name} will be merged into {keep.name}. Its {dup.rounds} round{dup.rounds === 1 ? '' : 's'} move to {keep.name} and {dup.name} disappears. This can't be undone.</>}
    </Dialog>
  )
}

function metres(a: admin.AdminLocation, b: admin.AdminLocation) {
  const r = 6_371_000, rad = (d: number) => (d * Math.PI) / 180
  const dLat = rad(b.latitude - a.latitude), dLon = rad(b.longitude - a.longitude)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLon / 2) ** 2
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)))
}

export function AdminLocations() {
  useAdminGuard()
  const [data, retry] = useLoad(() => admin.locations(), [])
  const [sel, setSel] = useState<admin.AdminLocation | null>(null)
  const [name, setName] = useState('')
  const [priv, setPriv] = useState(false)
  const [merging, setMerging] = useState(false)
  const [merge, setMerge] = useState<[admin.AdminLocation, admin.AdminLocation] | null>(null)
  const [busy, setBusy] = useState(false)
  async function act(f: () => Promise<unknown>, done?: string) {
    setBusy(true)
    try { await f(); if (done) toast(done); setSel(null); retry() } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  const all = data.state === 'ready' ? data.value : []
  return (
    <>
      <Bar title="Shoot locations" />
      <main>
        <Card title="Locations" sub="Tap a location to fix its name, or to merge a duplicate (e.g. two phones named the same club while offline) into the one to keep. Its rounds move across." />
        <Loading load={data} retry={retry}>{list => <>
          {!list.length && <p class="sub">No shoot locations yet. They appear as archers name them.</p>}
          {list.map(l => (
            <button class="tile" disabled={l.hidden} onClick={() => { setSel(l); setName(l.name); setPriv(l.isPrivate); setMerging(false) }}>
              <span class="grow"><b>{l.name}</b>{l.isPrivate && <span class="sub"> · PRIVATE</span>}
                <span class="sub" style="display:block">{l.rounds} round{l.rounds === 1 ? '' : 's'}{l.createdBy ? ` · named by ${l.createdBy}` : ''}{l.createdUtc ? ` · ${ukDate(l.createdUtc)}` : ''}</span></span>
            </button>
          ))}
          {list.some(l => l.hidden) && <div class="sub">Private locations belong to the archer who named them: their names and positions are hidden. They only appear under Possible duplicates if they clash with a public location.</div>}
        </>}</Loading>
      </main>
      <Dialog open={!!sel} title={sel?.name ?? ''} onClose={() => setSel(null)}
        actions={merging ? <button onClick={() => setMerging(false)}>Back</button>
          : <><button onClick={() => setSel(null)}>Cancel</button>
            <button disabled={busy || name.trim().length < 2} onClick={() => act(() => admin.rename(sel!.id, name, priv !== sel!.isPrivate ? priv : undefined), 'Saved.')}>Save</button></>}>
        {sel && !merging && <div class="col">
          <div class="sub">{sel.rounds} round{sel.rounds === 1 ? '' : 's'}</div>
          <Field label="Name"><input maxLength={60} value={name} onInput={e => setName((e.target as HTMLInputElement).value)} /></Field>
          <label class="row" style="flex-wrap:nowrap"><span class="grow"><b>Private</b><div class="sub">Only the archer who named it sees it</div></span>
            <input type="checkbox" style="width:auto" checked={priv} onChange={e => setPriv((e.target as HTMLInputElement).checked)} /></label>
          <button class="outlined" onClick={() => setMerging(true)}>Merge into another location…</button>
        </div>}
        {sel && merging && <div class="col">
          <div>Merge into which location? Nearest first.</div>
          {all.filter(o => o.id !== sel.id && !o.hidden).sort((a, b) => metres(sel, a) - metres(sel, b)).map(o => (
            <button class="row between plain" onClick={() => setMerge([sel, o])}>
              <span><b>{o.name}</b><div class="sub">{distanceText(Math.round(metres(sel, o)))} · {o.rounds} round{o.rounds === 1 ? '' : 's'}</div></span></button>
          ))}
        </div>}
      </Dialog>
      <MergeDialog pair={merge} onClose={() => setMerge(null)} onMerge={(dup, keep) => act(async () => toast(`Merged: ${await admin.merge(dup.id, keep.id)} rounds moved.`))} />
    </>
  )
}

const STATUS: Record<string, string> = { ACTIVE: 'in use', CANDIDATE: 'waiting (tests worse than the one in use)' }
const source = (s: string | null, by: string | null) =>
  s === 'RULE' ? 'Before Covid' : s === 'AUTO' ? 'Worked out' : s === 'DOUBTFUL' ? 'Doubtful' : s === 'ADMIN' ? `Set by ${by ?? 'an admin'}` : 'Not set yet'

export function AdminHandicap() {
  const account = useAdminGuard()
  const [data, retry] = useLoad(() => adminHandicap(), [])
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<any>(null)
  async function act(f: () => Promise<unknown>, done?: string) {
    setBusy(true)
    try { const r = await f(); toast(done ?? (typeof r === 'string' ? r : 'Done.')); retry() } catch (e) { toast(errorText(e)) } finally { setBusy(false) }
  }
  const name = (e: any) => `${e.champs === '3DS' ? '3D Championships' : 'Nationals'} ${e.year}`
  return (
    <>
      <Bar title="Handicap" />
      <main><Loading load={data} retry={retry}>{d => <>
        <Card title="NFAS results" sub="The server reads nfas.net every Monday night and refits the handicap if anything changed.">
          <button class="filled" disabled={busy} onClick={() => act(async () => (await requestHandicapFetch()).message ?? 'Requested.')}>Fetch at the next run</button>
          <div class="sub">To read them straight away, run FieldArcheryJobs.exe fetch on Odin.</div>
          {d.runs?.[0] && <><b>Last run{d.runs[0].finishedUtc ? ` · ${dt(d.runs[0].finishedUtc)}` : ' · not finished'}</b>
            {d.runs[0].outcome && <div class={d.runs[0].failed ? 'error' : 'sub'}>{d.runs[0].outcome}</div>}</>}
        </Card>
        <h3 class="section">Models</h3>
        {(d.models ?? []).map((m: any) => (
          <Card cls={m.status === 'ACTIVE' ? 'hi' : 'low'} title={`Model ${m.modelId} · ${STATUS[m.status] ?? 'replaced'}`}>
            <div class="sub" style="color:inherit">{dt(m.fittedUtc)} · {m.events} championships to {m.dataThroughYear}, {m.archers} archers, {m.dayScores} day scores</div>
            <div class="sub" style="color:inherit">Tests: hidden results {Number(m.holdoutRmse).toFixed(1)} points a day · bow bias {Number(m.switcherBias).toFixed(3)}</div>
            {m.note && <div class="sub">{m.note}</div>}
            {m.status !== 'ACTIVE' && account?.role === 'OWNER' && <button class="outlined" disabled={busy} onClick={() => act(() => activateHandicapModel(m.modelId), 'Model in use.')}>Use this model</button>}
          </Card>
        ))}
        <h3 class="section">Championships</h3>
        <div class="sub">Targets per day aren't published: the server works them out (before Covid all 40). Tap one to set it by hand.</div>
        {(d.events ?? []).map((e: any) => (
          <button class={`tile ${e.targetsSource === 'DOUBTFUL' ? 'warn' : ''}`} disabled={e.status !== 'RESULTS'} onClick={() => setEditing(e)}>
            <span class="grow"><b>{name(e)}</b>
              <span class="sub" style="display:block">{e.status === 'RESULTS' ? `${e.resultCount} results` : e.status === 'PLACINGS' ? 'Placings only, no scores' : 'No results published'}</span>
              {e.status === 'RESULTS' && e.targetsNote && <span class="sub" style="display:block">{e.targetsNote}</span>}</span>
            {e.status === 'RESULTS' && <span style="text-align:right"><span class="num-big">{e.targetsPerDay ?? '–'}</span><span class="sub" style="display:block">{source(e.targetsSource, e.targetsSetBy)}</span></span>}
          </button>
        ))}
      </>}</Loading></main>
      <Dialog open={!!editing} title={editing ? `${name(editing)}: targets a day` : ''} onClose={() => setEditing(null)}
        actions={<><button onClick={() => setEditing(null)}>Cancel</button>
          {[36, 40].map(n => <button onClick={() => { const e = editing; setEditing(null); act(() => setHandicapTargets(e.champs, e.year, n), 'Saved.') }}>{n}</button>)}
          {editing?.targetsSource === 'ADMIN' && <button onClick={() => { const e = editing; setEditing(null); act(() => setHandicapTargets(e.champs, e.year, null), 'Saved.') }}>Automatic</button>}</>}>
        {editing && <>Now {editing.targetsPerDay ?? '–'} ({source(editing.targetsSource, editing.targetsSetBy).toLowerCase()}). The handicap is refitted on the next weekly run.</>}
      </Dialog>
    </>
  )
}
