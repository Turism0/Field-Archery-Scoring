// Scoring rules, the same as the Android app's domain/Scoring.kt and the server's RoundValidator. Keep them in step.

export type Hit = 'INNER_KILL' | 'KILL' | 'WOUND'
export type TargetType = 'STANDARD' | 'PREDATOR_PREY' | 'FAN' | 'WOODSMAN'
export type RoundType = 'BIG_GAME' | 'WOODSMAN'
/** Woodsman arrows in order: W wound, K kill, M miss. */
export type WoodsmanArrow = 'W' | 'K' | 'M'

export type AnimalResult =
  | { state: 'PENDING' }
  | { state: 'MISSED' }
  | { state: 'SCORED'; arrowNo: number; hit: Hit }

export interface TargetScore {
  targetNo: number
  type: TargetType
  animals: AnimalResult[]
  /** A manual score for non-standard scoring; replaces the calculated one. */
  overridePoints: number | null
  /** Woodsman only, e.g. "WK". */
  arrows: string
}

export const HIT_NAMES: Record<Hit, string> = { INNER_KILL: 'Inner kill', KILL: 'Kill', WOUND: 'Wound' }
export const TARGET_TYPE_NAMES: Record<TargetType, string> = {
  STANDARD: 'Standard', PREDATOR_PREY: 'Predator / Prey', FAN: 'Fan', WOODSMAN: 'Woodsman',
}

export const MAX_ARROWS = 3
export const MAX_PER_ANIMAL = 24
export const MIN_FAN = 2
export const MAX_FAN = 6
export const WOODSMAN_MAX = 30
export const PENDING: AnimalResult = { state: 'PENDING' }
export const MISSED: AnimalResult = { state: 'MISSED' }

/** Big Game: arrow 1 inner kill 24, kill 20, wound 16; arrow 2 kill 14, wound 10; arrow 3 kill 8, wound 4. */
export function allowedHits(arrowNo: number): Hit[] {
  if (arrowNo === 1) return ['INNER_KILL', 'KILL', 'WOUND']
  if (arrowNo === 2 || arrowNo === 3) return ['KILL', 'WOUND']
  return []
}

export function bigGamePoints(arrowNo: number, hit: Hit): number {
  if (!allowedHits(arrowNo).includes(hit)) throw new Error(`${hit} is not valid on arrow ${arrowNo}`)
  if (arrowNo === 1) return hit === 'INNER_KILL' ? 24 : hit === 'KILL' ? 20 : 16
  if (arrowNo === 2) return hit === 'KILL' ? 14 : 10
  return hit === 'KILL' ? 8 : 4
}

export function animalPoints(a: AnimalResult): number {
  return a.state === 'SCORED' ? bigGamePoints(a.arrowNo, a.hit) : 0
}

/** Woodsman: wound 5 on any arrow; kill 30 / 20 / 10 on arrow 1 / 2 / 3 (and shooting stops); miss 0. */
export function woodsmanPoints(arrows: string): number {
  let total = 0
  for (let i = 0; i < arrows.length; i++) {
    const a = arrows[i]
    if (a === 'W') total += 5
    else if (a === 'K') total += WOODSMAN_MAX - i * 10
  }
  return total
}

export function woodsmanValid(arrows: string): boolean {
  return arrows.length <= MAX_ARROWS && /^[WKM]*$/.test(arrows) && !arrows.slice(0, -1).includes('K')
}

export function woodsmanFinished(arrows: string): boolean {
  return arrows.endsWith('K') || arrows.length === MAX_ARROWS
}

export function animalCountFor(type: TargetType): number {
  return type === 'STANDARD' ? 1 : type === 'PREDATOR_PREY' ? 2 : type === 'FAN' ? MIN_FAN : 0
}

export function newTarget(roundType: RoundType, targetNo: number): TargetScore {
  return roundType === 'WOODSMAN'
    ? { targetNo, type: 'WOODSMAN', animals: [], overridePoints: null, arrows: '' }
    : { targetNo, type: 'STANDARD', animals: [PENDING], overridePoints: null, arrows: '' }
}

/** Arrows that can be shot at an animal: the prey gets what the predator left. */
export function arrowsAvailable(type: TargetType, animals: AnimalResult[], index: number): number {
  if (type !== 'PREDATOR_PREY' || index === 0) return MAX_ARROWS
  const predator = animals[0]
  return predator.state === 'SCORED' ? MAX_ARROWS - predator.arrowNo : 0
}

export function calculatedPoints(t: TargetScore): number {
  return t.type === 'WOODSMAN' ? woodsmanPoints(t.arrows) : t.animals.reduce((s, a) => s + animalPoints(a), 0)
}

export function totalPoints(t: TargetScore): number {
  return t.overridePoints ?? calculatedPoints(t)
}

export function maxPoints(t: TargetScore): number {
  return t.type === 'WOODSMAN' ? WOODSMAN_MAX : t.animals.length * MAX_PER_ANIMAL
}

/** Fully scored: overridden, or every animal resolved (a prey with no arrows left counts as resolved). */
export function isComplete(t: TargetScore): boolean {
  if (t.overridePoints != null) return true
  if (t.type === 'WOODSMAN') return woodsmanFinished(t.arrows)
  return t.animals.every((a, i) =>
    a.state !== 'PENDING' || (t.animals[0].state !== 'PENDING' && arrowsAvailable(t.type, t.animals, i) === 0))
}

/** Nothing entered on this target yet. */
export function isBlank(t: TargetScore): boolean {
  return t.overridePoints == null && t.arrows === '' && t.animals.every(a => a.state === 'PENDING')
}

/** Resets the prey if the predator no longer leaves enough arrows for its result. */
function fixDependents(type: TargetType, animals: AnimalResult[]): AnimalResult[] {
  if (type !== 'PREDATOR_PREY') return animals
  const prey = animals[1]
  const available = arrowsAvailable(type, animals, 1)
  const ok = prey.state === 'SCORED' ? prey.arrowNo <= available
    : prey.state === 'MISSED' ? animals[0].state !== 'PENDING' : true
  return ok ? animals : [animals[0], PENDING]
}

/** Sets one animal's result (tapping the same result again clears it, as in the app). */
export function withAnimalResult(t: TargetScore, index: number, result: AnimalResult): TargetScore {
  if (result.state === 'SCORED') {
    if (result.arrowNo > arrowsAvailable(t.type, t.animals, index)) throw new Error('No arrows left for that')
    bigGamePoints(result.arrowNo, result.hit)
  }
  const animals = t.animals.slice()
  animals[index] = result
  return { ...t, animals: fixDependents(t.type, animals) }
}

export function sameResult(a: AnimalResult, b: AnimalResult): boolean {
  if (a.state !== b.state) return false
  return a.state !== 'SCORED' || (b.state === 'SCORED' && a.arrowNo === b.arrowNo && a.hit === b.hit)
}

/** Changes the target type, keeping whatever results still make sense. */
export function withType(t: TargetScore, type: TargetType, fanAnimals = MIN_FAN): TargetScore {
  if (type === 'FAN' && (fanAnimals < MIN_FAN || fanAnimals > MAX_FAN)) throw new Error(`Fan has ${MIN_FAN} to ${MAX_FAN} animals`)
  const count = type === 'FAN' ? fanAnimals : animalCountFor(type)
  const kept = Array.from({ length: count }, (_, i) => t.animals[i] ?? PENDING)
  return { ...t, type, animals: fixDependents(type, kept) }
}

export function withOverride(t: TargetScore, points: number | null): TargetScore {
  return { ...t, overridePoints: points }
}

export function withArrow(t: TargetScore, arrow: WoodsmanArrow): TargetScore {
  return t.type !== 'WOODSMAN' || woodsmanFinished(t.arrows) ? t : { ...t, arrows: t.arrows + arrow }
}

export function withoutLastArrow(t: TargetScore): TargetScore {
  return { ...t, arrows: t.arrows.slice(0, -1) }
}

export function cleared(t: TargetScore): TargetScore {
  return { ...t, animals: t.animals.map(() => PENDING), overridePoints: null, arrows: '' }
}

export function animalLabel(t: TargetScore, index: number): string {
  if (t.type === 'PREDATOR_PREY') return index === 0 ? 'Predator' : 'Prey'
  if (t.type === 'FAN') return `Animal ${index + 1}`
  return 'Animal'
}

export function ordinal(n: number): string {
  return n === 1 ? '1st' : n === 2 ? '2nd' : n === 3 ? '3rd' : `${n}th`
}

export function describeAnimal(a: AnimalResult): string {
  if (a.state === 'PENDING') return 'Not scored'
  if (a.state === 'MISSED') return 'Miss'
  return `${HIT_NAMES[a.hit]}, ${ordinal(a.arrowNo)} arrow`
}

export function describeArrows(arrows: string): string {
  if (!arrows) return 'Not scored'
  return [...arrows].map((a, i) => a === 'K' ? `Kill (${ordinal(i + 1)})` : a === 'W' ? 'Wound' : 'Miss').join(', ')
}

/** What has to match for two scorers' entries to be the same score (server GroupRules.MatchKey). */
export function matchKey(t: TargetScore): string {
  return [
    t.type, t.overridePoints ?? '-', totalPoints(t), t.type === 'WOODSMAN' ? t.arrows : '',
    t.animals.map((a, i) => `${i}:${a.state}:${a.state === 'SCORED' ? a.arrowNo : ''}:${a.state === 'SCORED' ? a.hit : ''}:${animalPoints(a)}`).join(';'),
  ].join('|')
}
