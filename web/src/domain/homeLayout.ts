// How Home is arranged (as the app's HomeLayout.kt): the tiles' order and any hidden. Kept on this device only.
export const TILES = ['SCORE', 'JOIN', 'HISTORY', 'STATS', 'HANDICAP', 'FRIENDS', 'LOCATIONS', 'ADMIN'] as const
export type TileId = typeof TILES[number]
export const TILE_TITLES: Record<TileId, string> = {
  SCORE: 'Score a round', JOIN: 'Join a live group', HISTORY: 'Score history', STATS: 'Stats', HANDICAP: 'Handicap calculator',
  FRIENDS: 'Friends', LOCATIONS: 'Shoot locations', ADMIN: 'Admin',
}
export const canHide = (id: TileId) => id !== 'SCORE'

export interface HomeLayout { order: TileId[]; hidden: TileId[] }
export const DEFAULT_LAYOUT: HomeLayout = { order: [...TILES], hidden: [] }

/** Every tile once (new ones at their usual place); Score a round never hidden. */
export function normalised(l: HomeLayout | undefined | null): HomeLayout {
  if (!l) return DEFAULT_LAYOUT
  const known = [...new Set(l.order)].filter(id => TILES.includes(id))
  TILES.forEach((id, i) => {
    if (known.includes(id)) return
    const before = TILES.slice(0, i).reverse().find(x => known.includes(x))
    known.splice(before ? known.indexOf(before) + 1 : 0, 0, id)
  })
  return { order: known, hidden: [...new Set(l.hidden)].filter(id => TILES.includes(id) && canHide(id)) }
}

/** Moves id by (−1 up, +1 down) among the tiles shown, swapping in the full order so others keep their places. */
export function move(l: HomeLayout, id: TileId, by: number, shown: TileId[]): HomeLayout {
  const visible = l.order.filter(x => shown.includes(x))
  const i = visible.indexOf(id), j = i + by
  if (i < 0 || j < 0 || j >= visible.length) return l
  const full = [...l.order]
  const a = full.indexOf(id), b = full.indexOf(visible[j])
  ;[full[a], full[b]] = [full[b], full[a]]
  return { ...l, order: full }
}

export function setHidden(l: HomeLayout, id: TileId, hide: boolean): HomeLayout {
  if (!canHide(id)) return l
  return { ...l, hidden: hide ? [...new Set([...l.hidden, id])] : l.hidden.filter(x => x !== id) }
}

export const isDefault = (l: HomeLayout) => JSON.stringify(normalised(l)) === JSON.stringify(DEFAULT_LAYOUT)
