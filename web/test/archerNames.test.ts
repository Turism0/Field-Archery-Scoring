// Names you give other archers (3.2.23). The same cases as the app's ArcherNamesTest.kt.
import { describe, expect, it } from 'vitest'
import { MAX_ARCHER_NAME, cleanArcherName, displayName, usernameUnder, withName } from '../src/domain/archerNames'

const names = { a1: 'Dave Smith' }

describe('archer names', () => {
  it('your name is shown, else the username', () => {
    expect(displayName('archer_dave', 'a1', names)).toBe('Dave Smith')
    expect(displayName('sue99', 'a2', names)).toBe('sue99')
    expect(displayName('Robin', null, names)).toBe('Robin')
  })
  it('the username goes underneath only when a name is shown instead', () => {
    expect(usernameUnder('archer_dave', 'a1', names)).toBe('archer_dave')
    expect(usernameUnder('sue99', 'a2', names)).toBeNull()
    expect(usernameUnder('dave smith', 'a1', names)).toBeNull()
  })
  it('names are tidied, capped, and blank removes them', () => {
    expect(cleanArcherName('Dave  Smith')).toBe('Dave Smith')
    expect(cleanArcherName('x'.repeat(60))!.length).toBe(MAX_ARCHER_NAME)
    expect(withName(names, 'a2', '  Sue ')).toEqual({ a1: 'Dave Smith', a2: 'Sue' })
    expect(withName(names, 'a1', '   ')).toEqual({})
  })
})
