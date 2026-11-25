// 3.2.25 (Pete): people were making duplicate shoot locations, and with no signal you couldn't pick one at all. Locations
// you can use are kept on this device (refreshed on each sync) so they can be found with no signal, and a new name is
// checked against them first: the same name (anywhere), a similar name (within SIMILAR_NAME_METRES, 3.2.28), or within SIMILAR_METRES. The name rules are the
// server's (DuplicateFinder.Key / Similar). The same as the app's domain/LocationMatch.kt; tested in test/locationMatch.test.ts.

export interface KnownPlace { id: string; name: string; latitude: number | null; longitude: number | null; isPrivate: boolean }
/** reasons: same_name, similar_name, nearby. */
export interface PlaceMatch { id: string; name: string; isPrivate: boolean; distanceMetres: number | null; reasons: string[] }

export const SIMILAR_METRES = 300
/** 3.2.28 (Pete, "option 5"): a similar (not the same) name only counts within this, or when either place has no pin. */
export const SIMILAR_NAME_METRES = 500
/** The server's catchment: a round within this of a location is put at it without asking (about a quarter mile). */
export const CATCHMENT_METRES = 402

const COMMON = new Set(['the', 'of', 'and', 'at', 'club', 'clubs', 'archers', 'archery', 'bowmen', 'bowman', 'field', 'fa', 'fac',
  'society', 'group', 'ground', 'grounds', 'company', 'co', 'ltd', 'nfas', 'target', 'targets', 'range', 'wood', 'woods'])

/** Great-circle distance in metres. */
export function metresBetween(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6_371_000, rad = (d: number) => d * Math.PI / 180
  const dLat = rad(lat2 - lat1), dLon = rad(lon2 - lon1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2
  return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)))
}

/** "The Bowmen of Foo-Bar!" → "foobar". All the words if they're all common ones. */
export function placeKey(name: string): string {
  const words: string[] = []
  let w = ''
  for (const c of name.toLowerCase() + ' ') {
    if (/[\p{L}\p{N}]/u.test(c)) { w += c; continue }
    if (c === '-' || c === "'" || c === '’') continue
    if (w) { words.push(w); w = '' }
  }
  const significant = words.filter(x => !COMMON.has(x))
  return (significant.length ? significant : words).join('')
}

function levenshtein(a: string, b: string): number {
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i), cur = new Array<number>(b.length + 1)
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
    ;[prev, cur] = [cur, prev]
  }
  return prev[b.length]
}

/** One contains the other (4+ letters), or at least 80% the same by edit distance. */
export function similarKeys(a: string, b: string): boolean {
  if (!a || !b) return false
  if (Math.min(a.length, b.length) >= 4 && (a.includes(b) || b.includes(a))) return true
  return 1 - levenshtein(a, b) / Math.max(a.length, b.length) >= 0.8
}

/**
 * 3.2.28: similarKeys for suggestions: one name inside the other only counts when the shorter is at least half the longer
 * ("sherwood" in "sherwoodforest" yes; "test" in "zzsimabcdefg" no); typos as before. The Admin rule (similarKeys) is unchanged.
 */
export function closeName(a: string, b: string): boolean {
  if (!a || !b) return false
  const short = Math.min(a.length, b.length), long = Math.max(a.length, b.length)
  if (short >= 4 && short * 2 >= long && (a.includes(b) || b.includes(a))) return true
  return 1 - levenshtein(a, b) / long >= 0.8
}

const pinned = (p: KnownPlace): p is KnownPlace & { latitude: number; longitude: number } => p.latitude != null && p.longitude != null

/** Places that might be the one being named (at lat / lon if it has a pin), best first, at most 5; skip: already turned down. */
export function placeMatches(name: string, lat: number | null, lon: number | null, known: KnownPlace[], skip: string[] = []): PlaceMatch[] {
  const k = placeKey(name)
  const out: PlaceMatch[] = []
  for (const p of known) {
    if (skip.includes(p.id)) continue
    const metres = lat != null && lon != null && pinned(p) ? Math.round(metresBetween(lat, lon, p.latitude, p.longitude)) : null
    const reasons: string[] = []
    const pk = placeKey(p.name)
    if (pk === k) reasons.push('same_name')
    else if (closeName(pk, k) && (metres == null || metres <= SIMILAR_NAME_METRES)) reasons.push('similar_name')
    if (metres != null && metres <= SIMILAR_METRES) reasons.push('nearby')
    if (reasons.length) out.push({ id: p.id, name: p.name, isPrivate: p.isPrivate, distanceMetres: metres, reasons })
  }
  return out.sort((a, b) => b.reasons.length - a.reasons.length || (a.distanceMetres ?? Infinity) - (b.distanceMetres ?? Infinity) ||
    (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)).slice(0, 5)
}

/** Places with a pin within radiusMetres, nearest first (with no signal, from this device's copy). */
export function nearbyPlaces(known: KnownPlace[], lat: number, lon: number, radiusMetres: number): { id: string; name: string; distanceMetres: number }[] {
  return known.filter(pinned).map(p => ({ id: p.id, name: p.name, distanceMetres: Math.round(metresBetween(lat, lon, p.latitude, p.longitude)) }))
    .filter(p => p.distanceMetres <= radiusMetres).sort((a, b) => a.distanceMetres - b.distanceMetres)
}

/** "Same name · 250 m away" for a suggestion. */
export function whyMatch(m: PlaceMatch): string {
  return [
    m.reasons.includes('same_name') ? 'Same name' : m.reasons.includes('similar_name') ? 'Similar name' : null,
    m.distanceMetres != null ? (m.distanceMetres < 1000 ? `${m.distanceMetres} m away` : m.distanceMetres < 10_000 ? `${(m.distanceMetres / 1000).toFixed(1)} km away` : `${Math.round(m.distanceMetres / 1000)} km away`) : null,
    m.isPrivate ? 'your private location' : null,
  ].filter(Boolean).join(' · ')
}
