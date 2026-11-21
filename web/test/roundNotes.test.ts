// Searching history by round notes (3.2.21). The same cases as the app's RoundNotesTest.kt.
import { describe, expect, it } from 'vitest'
import { notesMatch } from '../src/domain/roundNotes'
import { cleanNotes, MAX_NOTES } from '../src/domain/round'

const notes = 'New arrows, windy.\nStruggled on the uphill shots'

describe('round notes', () => {
  it('blank search matches everything', () => {
    expect(notesMatch(null, '')).toBe(true); expect(notesMatch(notes, '   ')).toBe(true)
  })
  it('every word must appear, any case and order', () => {
    expect(notesMatch(notes, 'windy')).toBe(true)
    expect(notesMatch(notes, 'UPHILL  arrows')).toBe(true)
    expect(notesMatch(notes, 'up')).toBe(true)
    expect(notesMatch(notes, 'windy rain')).toBe(false)
  })
  it('a search never matches a round without notes', () => {
    expect(notesMatch(null, 'windy')).toBe(false)
  })
  it('notes are trimmed, capped and blank is none', () => {
    expect(cleanNotes('  hi  ')).toBe('hi'); expect(cleanNotes('   ')).toBeNull(); expect(cleanNotes(null)).toBeNull()
    expect(cleanNotes('x'.repeat(600))!.length).toBe(MAX_NOTES)
  })
})
