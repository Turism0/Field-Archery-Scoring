// Suggesting a known location instead of a new one, and finding them with no signal (3.2.25). The same cases as the app's LocationMatchTest.kt.
import { describe, expect, it } from 'vitest'
import { closeName, nearbyPlaces, placeKey, placeMatches, similarKeys, whyMatch, type KnownPlace } from '../src/domain/locationMatch'

const club: KnownPlace = { id: 'c', name: 'Sherwood Archers', latitude: 53.2, longitude: -1.07, isPrivate: false }
const other: KnownPlace = { id: 'o', name: 'Kielder Bowmen', latitude: 55.2, longitude: -2.5, isPrivate: false }
const named: KnownPlace = { id: 'n', name: 'Nationals 2012', latitude: null, longitude: null, isPrivate: false }
const known = [club, other, named]

describe('location matching', () => {
  it('keys ignore club words, case and punctuation', () => {
    expect(placeKey('The Bowmen of Foo-Bar!')).toBe('foobar')
    expect(placeKey('Sherwood Archers')).toBe('sherwood')
    expect(placeKey("St. Mary's Field Archery Club")).toBe('stmarys')
    expect(placeKey('Archers Club')).toBe('archersclub')
  })
  it('similar names', () => {
    expect(similarKeys('sherwood', 'sherwod')).toBe(true)
    expect(similarKeys('sherwood', 'sherwoodforest')).toBe(true)
    expect(similarKeys('sherwood', 'kielder')).toBe(false)
    expect(similarKeys('abc', 'abcd')).toBe(false)
  })
  it('close names for suggestions (3.2.28)', () => {
    expect(closeName('sherwood', 'sherwod')).toBe(true)
    expect(closeName('sherwood', 'sherwoodforest')).toBe(true)
    expect(closeName('test', 'zzsimabcdefg')).toBe(false)
    expect(closeName('kent', 'kentishwoodsmen')).toBe(false)
    expect(similarKeys('kent', 'kentishwoodsmen')).toBe(true)
  })
  it('similar name only within 500 m or with no pin', () => {
    expect(placeMatches('Sherwod', 51, 0, known)).toEqual([])
    expect(placeMatches('Sherwod', 53.2 + 0.004, -1.07, known).map(m => [m.id, m.reasons])).toEqual([['c', ['similar_name']]])
  })
  it('same or similar name anywhere', () => {
    expect(placeMatches('Sherwood Bowmen', 51, 0, known).map(m => [m.id, m.reasons])).toEqual([['c', ['same_name']]])
    expect(placeMatches('Sherwod', null, null, known).map(m => m.id)).toEqual(['c'])
    expect(placeMatches('NATIONALS 2012', null, null, known).map(m => m.id)).toEqual(['n'])
  })
  it('any place within 300 m', () => {
    const near = placeMatches("Robin's Wood", 53.2 + 0.002, -1.07, known)
    expect(near.map(m => m.id)).toEqual(['c'])
    expect(near[0].reasons).toEqual(['nearby'])
    expect(near[0].distanceMetres! >= 200 && near[0].distanceMetres! <= 240).toBe(true)
    expect(placeMatches("Robin's Wood", 53.2 + 0.004, -1.07, known)).toEqual([])
  })
  it('name and place together come first, and skipped ones are left out', () => {
    const two = [{ id: 'x', name: 'Robin Hood Field', latitude: 53.2 + 0.001, longitude: -1.07, isPrivate: false }, club]
    expect(placeMatches('Sherwood', 53.2 + 0.001, -1.07, two).map(m => m.id)).toEqual(['c', 'x'])
    expect(placeMatches('Sherwood', 53.2 + 0.001, -1.07, two, ['c']).map(m => m.id)).toEqual(['x'])
  })
  it('nearby with no signal', () => {
    expect(nearbyPlaces(known, 53.2 + 0.003, -1.07, 402).map(p => p.id)).toEqual(['c'])
    expect(nearbyPlaces(known, 53.2 + 0.01, -1.07, 402)).toEqual([])
    expect(nearbyPlaces(known, 54, -1.5, 300_000).map(p => p.id)).toEqual(['c', 'o'])
  })
  it('why', () => {
    expect(whyMatch({ id: 'c', name: 'S', isPrivate: false, distanceMetres: 222, reasons: ['same_name', 'nearby'] })).toBe('Same name · 222 m away')
    expect(whyMatch({ id: 'c', name: 'S', isPrivate: true, distanceMetres: 1500, reasons: ['similar_name'] })).toBe('Similar name · 1.5 km away · your private location')
    expect(whyMatch({ id: 'c', name: 'S', isPrivate: false, distanceMetres: 236_400, reasons: ['same_name'] })).toBe('Same name · 236 km away')
  })
})
