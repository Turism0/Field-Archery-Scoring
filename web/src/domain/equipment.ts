// Equipment (3.2.24, Pete): an archer's bows and arrows. A bow is shot in one or more bow styles; arrows are used with
// one or more bows. New round offers the bows for the bow style picked and the arrows for the bow picked, starting on
// the last ones used with that bow style; a choice with fewer than two options isn't shown (the only one is used).
// Synced with the account, private to the archer. The same rules as the app's domain/Equipment.kt; tested in
// test/equipment.test.ts.
import type { Round } from './round'

export type BowType = 'LONGBOW' | 'FLATBOW' | 'RECURVE' | 'COMPOUND' | 'CROSSBOW' | 'HORSEBOW' | 'SELFBOW' | 'OTHER'
export const BOW_TYPES: { code: BowType; name: string }[] = [
  { code: 'LONGBOW', name: 'Longbow' }, { code: 'FLATBOW', name: 'Flatbow' }, { code: 'RECURVE', name: 'Recurve' },
  { code: 'COMPOUND', name: 'Compound' }, { code: 'CROSSBOW', name: 'Crossbow' }, { code: 'HORSEBOW', name: 'Horse bow' },
  { code: 'SELFBOW', name: 'Self bow' }, { code: 'OTHER', name: 'Other' },
]
export const bowTypeName = (t: BowType) => BOW_TYPES.find(x => x.code === t)?.name ?? t

export type FletchMaterial = 'FEATHER' | 'VANE' | 'OTHER'
export const FLETCH_MATERIALS: { code: FletchMaterial; name: string }[] = [
  { code: 'FEATHER', name: 'Feathers' }, { code: 'VANE', name: 'Vanes' }, { code: 'OTHER', name: 'Other' },
]
const materialName = (m: FletchMaterial) => FLETCH_MATERIALS.find(x => x.code === m)!.name

export interface Bow {
  id: string
  type: BowType
  make?: string | null
  model?: string | null
  poundageLb?: number | null
  bowStyles: string[]          // codes
  updatedUtc: string
  deleted?: boolean
  pending?: boolean            // waiting to go to the server
}

export interface ArrowSet {
  id: string
  make?: string | null
  model?: string | null
  lengthIn?: number | null
  arrowGrains?: number | null
  spine?: string | null
  fletchCount?: number | null
  fletchMaterial?: FletchMaterial | null
  fletchLengthIn?: number | null
  fletchColours?: string | null
  pointGrains?: number | null
  bowIds: string[]
  updatedUtc: string
  deleted?: boolean
  pending?: boolean
}

export type SpineKind = 'CARBON' | 'WOOD' | 'ALUMINIUM'
export interface Spine { text: string; kind: SpineKind }
export const SPINE_KIND_NAMES: Record<SpineKind, string> = { CARBON: 'Carbon', WOOD: 'Wood', ALUMINIUM: 'Aluminium' }

export const MAX_TEXT = 60, MAX_COLOURS = 100
/** A choice of "None" in a picker (so it doesn't go back to the last or only one). */
export const NONE = ''

/** 45 → "45", 45.5 → "45.5", 29.25 → "29.25". */
export function num(d: number): string {
  const r = Math.round(d * 100) / 100
  return Number.isInteger(r) ? String(r) : r.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

export const clean = (s: string | null | undefined): string | null => {
  const t = (s ?? '').trim().replace(/\s+/g, ' ')
  return t || null
}

// ---- Names ----

export const bowName = (b: Bow) => [clean(b.make), clean(b.model)].filter(Boolean).join(' ') || bowTypeName(b.type)
export const bowDetail = (b: Bow) => [bowTypeName(b.type), b.poundageLb != null ? `${num(b.poundageLb)} lb` : null].filter(Boolean).join(' · ')
export const bowLabel = (b: Bow) => bowName(b) + (b.poundageLb != null ? ` (${num(b.poundageLb)} lb)` : '')

export const arrowName = (a: ArrowSet) => [clean(a.make), clean(a.model)].filter(Boolean).join(' ') || 'Arrows'

export function fletches(a: ArrowSet): string | null {
  let what: string | null = null
  if (a.fletchCount != null && a.fletchMaterial && a.fletchMaterial !== 'OTHER') {
    const m = materialName(a.fletchMaterial).toLowerCase()
    what = `${a.fletchCount} ${a.fletchCount === 1 ? m.slice(0, -1) : m}`
  } else if (a.fletchCount != null) what = `${a.fletchCount} fletch${a.fletchCount === 1 ? '' : 'es'}`
  else if (a.fletchMaterial) what = materialName(a.fletchMaterial)
  const parts = [what, a.fletchLengthIn != null ? `${num(a.fletchLengthIn)}″` : null, clean(a.fletchColours)].filter(Boolean)
  return parts.length ? parts.join(', ') : null
}

export const arrowDetail = (a: ArrowSet) => [
  a.lengthIn != null ? `${num(a.lengthIn)}″` : null,
  a.arrowGrains != null ? `${num(a.arrowGrains)} gr` : null,
  a.spine ? `spine ${a.spine}` : null,
  a.pointGrains != null ? `${num(a.pointGrains)} gr point` : null,
  fletches(a),
].filter(Boolean).join(' · ')

export const arrowLabel = (a: ArrowSet) => arrowName(a) + (a.spine ? ` (${a.spine})` : '')

/** A round's bow and arrows for its scorecard ("Border Hex 7 (45.5 lb) · Port Orford Cedar (45-50)"); null = none. */
export function roundEquipmentLine(r: Round, bows: Bow[], arrows: ArrowSet[]): string | null {
  const b = r.bowId ? bows.find(x => x.id === r.bowId) : undefined
  const a = r.arrowId ? arrows.find(x => x.id === r.arrowId) : undefined
  return [b ? bowLabel(b) : null, a ? arrowLabel(a) : null].filter(Boolean).join(' · ') || null
}

// ---- Spine ----

const aluminium = (diameter: number, wall: number) => diameter >= 10 && diameter <= 29 && wall >= 4 && wall <= 30

/**
 * Tidies a spine into the usual way of writing it: carbon as the deflection (500; "0.500" or ".500" → 500), wood as the
 * pound range (45-50; "45/50", "45 to 50", "45–50#" → 45-50; a single "45#" → 45), aluminium as the size (2213;
 * "22-13", "22 13" → 2213). Null when it's blank or doesn't look like any of them.
 */
export function parseSpine(input: string | null | undefined): Spine | null {
  let s = (input ?? '').trim().toLowerCase()
  if (!s) return null
  const pounds = /#|lbs?|pounds?/.test(s)
  s = s.replace(/spine/g, '').replace(/#|lbs?|pounds?/g, '').replace(/\s*(?:to|[-–—/])\s*/g, '-').trim().replace(/\s+/g, ' ')
  if (!s) return null
  let m = /^0?\.(\d{3})$/.exec(s)
  if (m) { const v = Number(m[1]); return v >= 150 && v <= 999 ? { text: String(v), kind: 'CARBON' } : null }
  m = /^(\d{2,3})[- ](\d{2,3})$/.exec(s)
  if (m) {
    const a = Number(m[1]), b = Number(m[2])
    if (a < b && a >= 20 && b <= 120) return { text: `${a}-${b}`, kind: 'WOOD' }
    if (m[1].length === 2 && m[2].length === 2 && aluminium(a, b)) return { text: m[1] + m[2], kind: 'ALUMINIUM' }
    return null
  }
  if (!/^\d{2,4}$/.test(s)) return null
  const v = Number(s)
  if (pounds) return v >= 20 && v <= 120 ? { text: String(v), kind: 'WOOD' } : null
  if (s.length === 4) {
    if (aluminium(Number(s.slice(0, 2)), Number(s.slice(2)))) return { text: s, kind: 'ALUMINIUM' }
    return v >= 150 && v <= 2000 ? { text: String(v), kind: 'CARBON' } : null
  }
  if (s.length === 3) return v >= 150 ? { text: String(v), kind: 'CARBON' } : v <= 120 ? { text: String(v), kind: 'WOOD' } : null
  return v >= 20 ? { text: String(v), kind: 'WOOD' } : null
}

// ---- Choosing ----

// 3.2.27 (Pete): a bow with no bow style, or arrows with no bow, can be kept, but aren't offered for rounds until linked.
export function bowsFor(bows: Bow[], style: string | null | undefined): Bow[] {
  if (!style) return []
  return bows.filter(b => !b.deleted && b.bowStyles.includes(style))
    .sort((a, b) => bowName(a).toLowerCase().localeCompare(bowName(b).toLowerCase()) || (a.poundageLb ?? 0) - (b.poundageLb ?? 0))
}

export function arrowsFor(arrows: ArrowSet[], bowId: string | null | undefined): ArrowSet[] {
  if (!bowId) return []
  return arrows.filter(a => !a.deleted && a.bowIds.includes(bowId)).sort((a, b) => arrowLabel(a).toLowerCase().localeCompare(arrowLabel(b).toLowerCase()))
}

export interface EquipmentChoice { bows: Bow[]; bowId: string | null; arrows: ArrowSet[]; arrowId: string | null }
export const showBows = (c: EquipmentChoice) => c.bows.length >= 2
export const showArrows = (c: EquipmentChoice) => c.arrows.length >= 2

/**
 * The bows and arrows to offer for a bow style, starting on bowId / arrowId if they still fit (what's picked so far),
 * else the last ones used with that bow style, else the only one. NONE picked: none.
 */
export function chooseEquipment(bows: Bow[], arrows: ArrowSet[], style: string | null | undefined,
  o: { bowId?: string | null; arrowId?: string | null; lastBowId?: string | null; lastArrowId?: string | null } = {}): EquipmentChoice {
  const forStyle = bowsFor(bows, style)
  const bow = o.bowId === NONE ? null
    : [o.bowId, o.lastBowId].find(id => id && forStyle.some(b => b.id === id)) ?? (forStyle.length === 1 ? forStyle[0].id : null)
  const forBow = arrowsFor(arrows, bow)
  const arrow = o.arrowId === NONE ? null
    : [o.arrowId, o.lastArrowId].find(id => id && forBow.some(a => a.id === id)) ?? (forBow.length === 1 ? forBow[0].id : null)
  return { bows: forStyle, bowId: bow ?? null, arrows: forBow, arrowId: arrow ?? null }
}

/** The bow and arrows of your most recently started round with this bow style that had a bow. */
export function lastUsed(rounds: Round[], style: string | null | undefined): { bowId: string | null; arrowId: string | null } {
  let best: Round | null = null
  for (const r of rounds) if (r.bowStyle === style && r.bowId && (!best || r.createdUtc > best.createdUtc)) best = r
  return { bowId: best?.bowId ?? null, arrowId: best?.arrowId ?? null }
}

// ---- Forms ----

/** A decimal typed by the archer ("45.5", "45,5", "45 lb"); null when blank; NaN when it isn't a number. */
export function parseNumber(text: string): number | null {
  const t = text.trim().toLowerCase().replace(/,/g, '.').replace(/(lbs?|gr|grains?|in|"|″|')$/, '').trim()
  if (!t) return null
  const n = Number(t)
  return Number.isFinite(n) && /^[-+]?(\d+\.?\d*|\.\d+)$/.test(t) ? n : NaN
}

function check(p: string[], v: number | null, min: number, max: number, what: string) {
  if (v == null) return
  if (Number.isNaN(v)) p.push(`${what} should be a number.`)
  else if (v < min || v > max) p.push(`${what} must be between ${num(min)} and ${num(max)}.`)
}

export interface BowForm { type: BowType | null; make: string; model: string; poundage: string; bowStyles: string[] }
export const emptyBowForm = (): BowForm => ({ type: null, make: '', model: '', poundage: '', bowStyles: [] })

export function bowProblems(f: BowForm): string[] {
  const p: string[] = []
  if (!f.type) p.push('Pick the bow type.')
  if (!clean(f.make) && !clean(f.model)) p.push('Give the bow a make or model.')
  if (f.make.trim().length > MAX_TEXT || f.model.trim().length > MAX_TEXT) p.push(`Make and model can be up to ${MAX_TEXT} characters.`)
  check(p, parseNumber(f.poundage), 5, 250, 'Poundage')
  return p
}

export function bowFromForm(f: BowForm, id: string, updatedUtc: string): Bow | null {
  if (bowProblems(f).length) return null
  return { id, type: f.type!, make: clean(f.make), model: clean(f.model), poundageLb: parseNumber(f.poundage), bowStyles: [...f.bowStyles], updatedUtc }
}

export const bowForm = (b: Bow): BowForm =>
  ({ type: b.type, make: b.make ?? '', model: b.model ?? '', poundage: b.poundageLb != null ? num(b.poundageLb) : '', bowStyles: [...b.bowStyles] })

export interface ArrowForm {
  make: string; model: string; length: string; arrowWeight: string; spine: string; fletchCount: string
  fletchMaterial: FletchMaterial | null; fletchLength: string; fletchColours: string; pointWeight: string; bowIds: string[]
}
export const emptyArrowForm = (): ArrowForm => ({ make: '', model: '', length: '', arrowWeight: '', spine: '', fletchCount: '',
  fletchMaterial: null, fletchLength: '', fletchColours: '', pointWeight: '', bowIds: [] })

export function arrowProblems(f: ArrowForm): string[] {
  const p: string[] = []
  if (!clean(f.make) && !clean(f.model)) p.push('Give the arrows a make or model.')
  if (f.make.trim().length > MAX_TEXT || f.model.trim().length > MAX_TEXT) p.push(`Make and model can be up to ${MAX_TEXT} characters.`)
  check(p, parseNumber(f.length), 10, 40, 'Arrow length')
  check(p, parseNumber(f.arrowWeight), 50, 2000, 'Arrow weight')
  check(p, parseNumber(f.pointWeight), 10, 1000, 'Point weight')
  check(p, parseNumber(f.fletchLength), 0.5, 9, 'Fletch length')
  const n = parseNumber(f.fletchCount)
  if (n != null && (Number.isNaN(n) || !Number.isInteger(n) || n < 1 || n > 8)) p.push('Fletches must be a whole number from 1 to 8.')
  if (f.fletchColours.trim().length > MAX_COLOURS) p.push(`Fletch colours can be up to ${MAX_COLOURS} characters.`)
  if (f.spine.trim() && !parseSpine(f.spine)) p.push('Spine should look like 500 (carbon), 45-50 (wood) or 2213 (aluminium).')
  return p
}

export function arrowsFromForm(f: ArrowForm, id: string, updatedUtc: string): ArrowSet | null {
  if (arrowProblems(f).length) return null
  const n = parseNumber(f.fletchCount)
  return {
    id, make: clean(f.make), model: clean(f.model), lengthIn: parseNumber(f.length), arrowGrains: parseNumber(f.arrowWeight),
    spine: parseSpine(f.spine)?.text ?? null, fletchCount: n == null ? null : Math.trunc(n), fletchMaterial: f.fletchMaterial,
    fletchLengthIn: parseNumber(f.fletchLength), fletchColours: clean(f.fletchColours), pointGrains: parseNumber(f.pointWeight),
    bowIds: [...f.bowIds], updatedUtc,
  }
}

const n2s = (v: number | null | undefined) => (v != null ? num(v) : '')
export const arrowForm = (a: ArrowSet): ArrowForm => ({
  make: a.make ?? '', model: a.model ?? '', length: n2s(a.lengthIn), arrowWeight: n2s(a.arrowGrains), spine: a.spine ?? '',
  fletchCount: a.fletchCount != null ? String(a.fletchCount) : '', fletchMaterial: a.fletchMaterial ?? null,
  fletchLength: n2s(a.fletchLengthIn), fletchColours: a.fletchColours ?? '', pointWeight: n2s(a.pointGrains), bowIds: [...a.bowIds],
})
