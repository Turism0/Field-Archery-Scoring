// Small shared pieces: hash routing, data that reloads when the device's data changes, and UI bits.
import type { ComponentChildren } from 'preact'
import { useEffect, useRef, useState } from 'preact/hooks'
import { onChange } from '../data/db'

// ---- routing: #/score/<id>?t=3 ----
export function useRoute(): string {
  const [hash, setHash] = useState(location.hash.slice(1) || '/')
  useEffect(() => {
    const h = () => setHash(location.hash.slice(1) || '/')
    addEventListener('hashchange', h)
    return () => removeEventListener('hashchange', h)
  }, [])
  return hash
}
export const go = (path: string) => { location.hash = path }
export const back = () => (history.length > 1 ? history.back() : go('/'))
export const replace = (path: string) => { location.replace(`#${path}`) }

/** Loads something and loads it again whenever the device's data changes. */
export function useData<T>(load: () => Promise<T>, deps: unknown[] = []): T | undefined {
  const [value, setValue] = useState<T>()
  useEffect(() => {
    let alive = true
    const run = () => load().then(v => { if (alive) setValue(v) })
    run()
    const off = onChange(run)
    return () => { alive = false; off() }
  }, deps)
  return value
}

// ---- one-off messages ----
let showToast: (m: string) => void = () => {}
export const toast = (m: string) => showToast(m)
export function Toasts() {
  const [msg, setMsg] = useState<string | null>(null)
  const timer = useRef<number>()
  showToast = (m: string) => { setMsg(m); clearTimeout(timer.current); timer.current = window.setTimeout(() => setMsg(null), 3500) }
  return msg ? <div class="toast" role="status">{msg}</div> : null
}

// ---- pieces ----
/** Wide window (about 1000px+): the desktop layout with a sidebar. */
export const DESKTOP = '(min-width: 1000px)'
export function useDesktop(): boolean {
  const [d, setD] = useState(() => matchMedia(DESKTOP).matches)
  useEffect(() => {
    const m = matchMedia(DESKTOP)
    const h = () => setD(m.matches)
    m.addEventListener('change', h)
    return () => m.removeEventListener('change', h)
  }, [])
  return d
}

/** Sections reached from the desktop sidebar: no back arrow there. */
const TOP_LEVEL = ['/', '/new', '/join', '/history', '/stats', '/handicap', '/friends', '/map', '/admin', '/profile']
const isTopLevel = () => TOP_LEVEL.includes((location.hash.slice(1) || '/').split('?')[0])

export function Bar({ title, onBack, children }: { title: string; onBack?: (() => void) | false; children?: ComponentChildren }) {
  const desktop = useDesktop()
  if (desktop && onBack === undefined && isTopLevel()) onBack = false
  return (
    <header class="bar">
      {onBack !== false && <button class="icon" aria-label="Back" onClick={onBack ?? back}>←</button>}
      <h1>{title}</h1>
      {children}
    </header>
  )
}

export function Card({ title, sub, children, cls = '' }: { title?: string; sub?: string; children?: ComponentChildren; cls?: string }) {
  return (
    <section class={`card ${cls}`}>
      {title && <h2>{title}</h2>}
      {sub && <div class="sub">{sub}</div>}
      {children}
    </section>
  )
}

export function Seg<T extends string | number>({ options, value, label, onChange }: {
  options: T[]; value: T | undefined; label: (v: T) => string; onChange: (v: T) => void
}) {
  return (
    <div class="seg" role="radiogroup">
      {options.map(o => (
        <button type="button" role="radio" aria-checked={o === value} class={o === value ? 'on' : ''} onClick={() => onChange(o)}>{label(o)}</button>
      ))}
    </div>
  )
}

export function Select<T extends string>({ label, value, options, optionLabel, onChange, none }: {
  label: string; value: T | undefined; options: T[]; optionLabel: (v: T) => string; onChange: (v: T | undefined) => void; none?: string
}) {
  return (
    <label class="field">{label}
      <select value={value ?? ''} onChange={e => onChange(((e.target as HTMLSelectElement).value || undefined) as T | undefined)}>
        {(none !== undefined || value === undefined) && <option value="">{none ?? 'Choose…'}</option>}
        {options.map(o => <option value={o}>{optionLabel(o)}</option>)}
      </select>
    </label>
  )
}

export function Field({ label, children, help }: { label: string; children: ComponentChildren; help?: string }) {
  return <label class="field">{label}{children}{help && <span class="sub">{help}</span>}</label>
}

/** A modal dialog (the browser's own, so it works with the keyboard and screen readers). */
export function Dialog({ open, title, children, actions, onClose }: {
  open: boolean; title: string; children?: ComponentChildren; actions: ComponentChildren; onClose?: () => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (open && !d.open) d.showModal()
    if (!open && d.open) d.close()
  }, [open])
  return (
    <dialog ref={ref} onCancel={e => { e.preventDefault(); onClose?.() }}>
      <h3>{title}</h3>
      {children}
      <div class="actions">{actions}</div>
    </dialog>
  )
}

export const today = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
export const ukDate = (iso: string) =>
  new Date(`${iso.slice(0, 10)}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
export const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e))

/** Running as an installed app (Home Screen), not in a browser tab. */
export const installed = () =>
  matchMedia('(display-mode: standalone)').matches || (navigator as unknown as { standalone?: boolean }).standalone === true
export const isIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent)

/** One decimal place, UK style. */
export const one = (v: number) => v.toFixed(1)

export function Tabs({ tabs, value, onChange }: { tabs: string[]; value: number; onChange: (i: number) => void }) {
  return (
    <div class="tabs" role="tablist">
      {tabs.map((t, i) => <button role="tab" aria-selected={i === value} class={i === value ? 'on' : ''} onClick={() => onChange(i)}>{t}</button>)}
    </div>
  )
}

/** Something loaded from the server: loading, failed (with try again) or ready. */
export type Load<T> = { state: 'loading' } | { state: 'failed'; message: string } | { state: 'ready'; value: T }

export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []): [Load<T>, () => void] {
  const [v, setV] = useState<Load<T>>({ state: 'loading' })
  const [n, setN] = useState(0)
  useEffect(() => {
    let alive = true
    load().then(value => alive && setV({ state: 'ready', value }), e => alive && setV({ state: 'failed', message: errorText(e) }))
    return () => { alive = false }
  }, [...deps, n])
  return [v, () => setN(x => x + 1)]
}

export function Loading<T>({ load, retry, children }: { load: Load<T>; retry: () => void; children: (v: T) => ComponentChildren }) {
  if (load.state === 'loading') return <p class="sub" style="text-align:center">Loading…</p>
  if (load.state === 'failed') return <div class="card err">{load.message}<button class="tonal" onClick={retry}>Try again</button></div>
  return <>{children(load.value)}</>
}
