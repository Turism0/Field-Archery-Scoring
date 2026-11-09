// Rounds, archers and round JSON (the API's shape, as the Android app sends it).
import {
  type RoundType, type TargetScore, type TargetType, type AnimalResult, type Hit,
  newTarget, isComplete, totalPoints, maxPoints, animalPoints, calculatedPoints, MAX_PER_ANIMAL, WOODSMAN_MAX,
} from './scoring'

export type ArcherClass = 'GENTS' | 'LADIES'
export type AgeGroup = 'ADULT' | 'JUNIOR' | 'CUB'
export type RoundStatus = 'IN_PROGRESS' | 'COMPLETED'
/** A round's place in a group on this device (as the app's GroupRole). */
export type GroupRole = 'SCORER' | 'FRIEND' | 'GUEST' | 'MIRROR'

export const CLASS_NAMES: Record<ArcherClass, string> = { GENTS: 'Gents', LADIES: 'Ladies' }
export const AGE_NAMES: Record<AgeGroup, string> = { ADULT: 'Adult', JUNIOR: 'Junior', CUB: 'Cub' }
export const ROUND_TYPE_NAMES: Record<RoundType, string> = { BIG_GAME: 'Big Game', WOODSMAN: 'Woodsman' }
export const ROUND_TYPE_HELP: Record<RoundType, string> = {
  BIG_GAME: '3D animal targets, up to 3 arrows each: inner kill, kill or wound. Includes predator/prey and fan targets.',
  WOODSMAN: 'One animal per target: shoot until you kill it, up to 3 arrows. Wound 5 on any arrow; kill 30, 20 or 10 on arrow 1, 2 or 3; miss 0.',
}

export const BOW_STYLES: { code: string; name: string }[] = [
  { code: 'AFB', name: 'American Flatbow' }, { code: 'BB', name: 'Barebow' }, { code: 'BH', name: 'Bowhunter' },
  { code: 'CL', name: 'Compound Limited' }, { code: 'XB', name: 'Crossbow' }, { code: 'TXB', name: 'Traditional Crossbow' },
  { code: 'FS', name: 'Freestyle' }, { code: 'HT', name: 'Hunting Tackle' }, { code: 'LB', name: 'Longbow' },
  { code: 'PV', name: 'Primitive' }, { code: 'TD', name: 'Thumb Draw' }, { code: 'TB', name: 'Traditional Bowhunter' },
  { code: 'UL', name: 'Unlimited' },
]
export const bowName = (code: string) => BOW_STYLES.find(b => b.code === code)?.name ?? code

export const MAX_TARGETS = 100
export const MAX_GROUP = 8

/** Adult 16+, Junior 12–15, Cub under 12, on the shoot date. */
export function ageGroupFor(dateOfBirth: string, shootDate: string): AgeGroup | null {
  if (!dateOfBirth || shootDate < dateOfBirth) return null
  const [by, bm, bd] = dateOfBirth.split('-').map(Number)
  const [sy, sm, sd] = shootDate.split('-').map(Number)
  let age = sy - by
  if (sm < bm || (sm === bm && sd < bd)) age--
  return age >= 16 ? 'ADULT' : age >= 12 ? 'JUNIOR' : 'CUB'
}

export interface Round {
  id: string
  shootDate: string          // yyyy-mm-dd
  targetCount: number
  archerClass: ArcherClass
  ageGroup: AgeGroup
  bowStyle: string           // code
  status: RoundStatus
  type: RoundType
  targets: TargetScore[]     // one per target, by number
  createdUtc: string
  updatedUtc: string
  /** Waiting to go to the server (or as a request to a friend). */
  pending: boolean
  failed?: boolean
  deleted?: boolean
  locationId?: string | null
  locationName?: string | null
  /** GPS position taken when the round started (stays on this device; only used to ask what's nearby). */
  fixLat?: number | null
  fixLon?: number | null
  /** PENDING_LOOKUP: position waiting to be looked up; NEEDS_NAME: nothing known nearby. */
  locationState?: 'PENDING_LOOKUP' | 'NEEDS_NAME' | null
  groupId?: string | null
  groupRole?: GroupRole | null
  groupName?: string | null      // someone else's name in a group
  groupArcherId?: string | null  // a friend's account
  groupOrder?: number | null
  /** The target the archer started on (2.7): just noted on the scorecard; targets keep their numbers. */
  startTarget?: number | null
}

export function target(r: Round, n: number): TargetScore {
  return r.targets.find(t => t.targetNo === n) ?? newTarget(r.type, n)
}

export const roundTotal = (r: Round) => r.targets.reduce((s, t) => s + totalPoints(t), 0)
export const targetsComplete = (r: Round) => r.targets.filter(isComplete).length
export function firstUnscored(r: Round): number | null {
  for (let n = 1; n <= r.targetCount; n++) if (!isComplete(target(r, n))) return n
  return null
}
/** No skipping ahead: scored targets, or the next one to score. */
export function canGoTo(r: Round, n: number): boolean {
  return n >= 1 && n <= r.targetCount && (isComplete(target(r, n)) || n === firstUnscored(r))
}

export const classLine = (r: { archerClass: ArcherClass; ageGroup: AgeGroup; bowStyle: string }) =>
  `${CLASS_NAMES[r.archerClass]} ${AGE_NAMES[r.ageGroup]} ${r.bowStyle}`

export function targetsLine(r: Round): string {
  const n = r.targetCount
  const base = `${n} target${n === 1 ? '' : 's'}`
  if (r.type === 'WOODSMAN') return base
  const pp = r.targets.filter(t => t.type === 'PREDATOR_PREY').length
  const fans = r.targets.filter(t => t.type === 'FAN').length
  if (!pp && !fans) return base
  let animals = 0
  for (let i = 1; i <= n; i++) animals += target(r, i).animals.length
  const extras = [pp ? `${pp} predator/prey` : '', fans ? `${fans} fan${fans === 1 ? '' : 's'}` : ''].filter(Boolean).join(', ')
  return `${base} · ${animals} animals (${extras})`
}

export interface RoundStats {
  total: number
  targetsScored: number
  averagePerAnimal: number | null
  maxPerAnimal: number
  innerKills: number; kills: number; wounds: number; misses: number
  byArrow: number[]   // [arrow1, arrow2, arrow3]
  overrides: number
  /** Animals on fully scored targets (predator/prey = 2, fan = n; Woodsman: targets). */
  animalsScored: number
  /** Points on fully scored targets as a share of the maximum, 0..1. */
  shareOfMax: number | null
  /** Rates over animals (Woodsman: targets) whose arrows were recorded: fully scored, not overridden. */
  firstArrowRate: number | null
  killRate: number | null
  missRate: number | null
}

const rate = <T,>(list: T[], f: (x: T) => boolean) => list.length ? list.filter(f).length / list.length : null

/** Running stats (same rules as the app): averages per animal over fully scored targets. */
export function roundStats(r: Round): RoundStats {
  const complete = r.targets.filter(isComplete)
  const overrides = r.targets.filter(t => t.overridePoints != null).length
  if (r.type === 'WOODSMAN') {
    const recordedTargets = r.targets.filter(t => t.overridePoints == null)
    const arrows = recordedTargets.map(t => t.arrows).join('')
    const recorded = complete.filter(t => t.overridePoints == null)
    const sum = complete.reduce((s, t) => s + totalPoints(t), 0)
    return {
      total: roundTotal(r), targetsScored: complete.length, maxPerAnimal: WOODSMAN_MAX,
      averagePerAnimal: complete.length ? sum / complete.length : null,
      innerKills: 0, kills: [...arrows].filter(a => a === 'K').length, wounds: [...arrows].filter(a => a === 'W').length,
      misses: [...arrows].filter(a => a === 'M').length,
      byArrow: [0, 1, 2].map(i => recordedTargets.filter(t => t.arrows[i] === 'K').length),
      overrides, animalsScored: complete.length,
      shareOfMax: complete.length ? sum / (complete.length * WOODSMAN_MAX) : null,
      firstArrowRate: rate(recorded, t => t.arrows[0] === 'K'),
      killRate: rate(recorded, t => t.arrows.endsWith('K')),
      missRate: rate(recorded, t => calculatedPoints(t) === 0),
    }
  }
  const results = r.targets.filter(t => t.overridePoints == null).flatMap(t => t.animals)
  const scored = results.filter((a): a is Extract<AnimalResult, { state: 'SCORED' }> => a.state === 'SCORED')
  const animals = complete.reduce((s, t) => s + t.animals.length, 0)
  const recorded = complete.filter(t => t.overridePoints == null).flatMap(t => t.animals)
  const max = complete.reduce((s, t) => s + maxPoints(t), 0)
  const sum = complete.reduce((s, t) => s + totalPoints(t), 0)
  return {
    total: roundTotal(r), targetsScored: complete.length, maxPerAnimal: MAX_PER_ANIMAL,
    averagePerAnimal: animals ? sum / animals : null,
    innerKills: scored.filter(a => a.hit === 'INNER_KILL').length,
    kills: scored.filter(a => a.hit === 'KILL').length,
    wounds: scored.filter(a => a.hit === 'WOUND').length,
    misses: results.filter(a => a.state === 'MISSED').length,
    byArrow: [1, 2, 3].map(n => scored.filter(a => a.arrowNo === n).length),
    overrides, animalsScored: animals,
    shareOfMax: max ? sum / max : null,
    firstArrowRate: rate(recorded, a => a.state === 'SCORED' && a.arrowNo === 1),
    killRate: rate(recorded, a => a.state === 'SCORED' && a.hit !== 'WOUND'),
    missRate: rate(recorded, a => a.state === 'MISSED'),
  }
}

/** Every target is scored. */
export const isFullyScored = (r: Round) => r.targets.length === r.targetCount && r.targets.every(isComplete)

export const nowUtc = () => new Date().toISOString()   // milliseconds, like the server keeps

export function newRound(p: {
  shootDate: string; targetCount: number; archerClass: ArcherClass; ageGroup: AgeGroup; bowStyle: string; type: RoundType
  id?: string; groupId?: string | null; groupRole?: GroupRole | null; groupName?: string | null; groupArcherId?: string | null
  groupOrder?: number | null; locationId?: string | null; locationName?: string | null; startTarget?: number | null
}): Round {
  const now = nowUtc()
  return {
    id: p.id ?? crypto.randomUUID(), shootDate: p.shootDate, targetCount: p.targetCount, archerClass: p.archerClass,
    ageGroup: p.ageGroup, bowStyle: p.bowStyle, status: 'IN_PROGRESS', type: p.type,
    targets: Array.from({ length: p.targetCount }, (_, i) => newTarget(p.type, i + 1)),
    createdUtc: now, updatedUtc: now, pending: true, groupId: p.groupId ?? null, groupRole: p.groupRole ?? null,
    groupName: p.groupName ?? null, groupArcherId: p.groupArcherId ?? null, groupOrder: p.groupOrder ?? null,
    locationId: p.locationId ?? null, locationName: p.locationName ?? null,
    startTarget: p.startTarget != null && p.startTarget >= 1 && p.startTarget <= p.targetCount ? p.startTarget : null,
  }
}

// ---- JSON (server/README.md) ----

export function targetToJson(t: TargetScore): Record<string, unknown> {
  return {
    targetNo: t.targetNo, type: t.type, overridePoints: t.overridePoints, points: totalPoints(t),
    animals: t.animals.map((a, i) => ({
      index: i, state: a.state, arrowNo: a.state === 'SCORED' ? a.arrowNo : null,
      hit: a.state === 'SCORED' ? a.hit : null, points: animalPoints(a),
    })),
    ...(t.type === 'WOODSMAN' ? { arrows: t.arrows } : {}),
  }
}

interface AnimalJson { index: number; state: string; arrowNo: number | null; hit: string | null }
export interface TargetJson { targetNo: number; type: string; overridePoints: number | null; animals: AnimalJson[]; arrows?: string | null }

export function targetFromJson(j: TargetJson): TargetScore {
  const animals = [...(j.animals ?? [])].sort((a, b) => a.index - b.index).map<AnimalResult>(a =>
    a.state === 'SCORED' ? { state: 'SCORED', arrowNo: a.arrowNo!, hit: a.hit as Hit }
      : a.state === 'MISSED' ? { state: 'MISSED' } : { state: 'PENDING' })
  return { targetNo: j.targetNo, type: j.type as TargetType, animals, overridePoints: j.overridePoints ?? null, arrows: j.arrows ?? '' }
}

export function roundToJson(r: Round): Record<string, unknown> {
  return {
    shootDate: r.shootDate, targetCount: r.targetCount, archerClass: r.archerClass === 'LADIES' ? 'L' : 'G',
    ageGroup: r.ageGroup, bowStyle: r.bowStyle, status: r.status, totalPoints: roundTotal(r),
    clientCreatedUtc: r.createdUtc, clientUpdatedUtc: r.updatedUtc, locationId: r.locationId ?? null,
    roundType: r.type, groupId: r.groupId ?? null, startTarget: r.startTarget ?? null,
    targets: [...r.targets].sort((a, b) => a.targetNo - b.targetNo).map(targetToJson),
  }
}

export function roundFromJson(j: any): Round {
  return {
    id: j.roundId, shootDate: String(j.shootDate).slice(0, 10), targetCount: j.targetCount,
    archerClass: j.archerClass === 'L' ? 'LADIES' : 'GENTS', ageGroup: j.ageGroup, bowStyle: j.bowStyle,
    status: j.status, type: j.roundType === 'WOODSMAN' ? 'WOODSMAN' : 'BIG_GAME',
    targets: (j.targets ?? []).map(targetFromJson), createdUtc: j.clientCreatedUtc, updatedUtc: j.clientUpdatedUtc,
    pending: false, locationId: j.locationId ?? null, locationName: j.locationName ?? null, startTarget: j.startTarget ?? null,
  }
}

/** Share of the maximum, for checks. */
export const shareOfMax = (t: TargetScore) => maxPoints(t) ? totalPoints(t) / maxPoints(t) : 0

export type LocationStatus = 'NONE' | 'LOOKING' | 'NEEDS_NAME' | 'SET'
export function locationStatus(r: Round): LocationStatus {
  if (r.locationId) return 'SET'
  if (r.locationState === 'NEEDS_NAME') return 'NEEDS_NAME'
  if (r.locationState === 'PENDING_LOOKUP' && r.fixLat != null) return 'LOOKING'
  return 'NONE'
}
