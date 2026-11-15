// Making a location private (3.2.12). The same cases as LocationPrivacyTest.kt.
import { describe, expect, it } from 'vitest'
import { deleteLocationWarning, makePrivateWarning } from '../src/domain/locationPrivacy'

describe('making a location private', () => {
  it("doesn't ask when nobody else has shot there", () => {
    expect(makePrivateWarning('Home', 0, 0)).toBeNull()
  })

  it('warns about one round', () => {
    expect(makePrivateWarning('Home', 1, 1)).toBe(
      "1 other archer has 1 round at Home. Making it private takes it off their scorecard, and it won't go back on if you make it public again.")
  })

  it('warns about several archers and rounds', () => {
    expect(makePrivateWarning('Bowland Wood', 3, 7)).toBe(
      "3 other archers have 7 rounds at Bowland Wood. Making it private takes it off their scorecards, and it won't go back on if you make it public again.")
    expect(makePrivateWarning('Home', 1, 4)).toBe(
      "1 other archer has 4 rounds at Home. Making it private takes it off their scorecards, and it won't go back on if you make it public again.")
  })

  it('warns before deleting a location', () => {
    expect(deleteLocationWarning('Home', 0)).toBe("Delete Home? This can't be undone.")
    expect(deleteLocationWarning('Home', 1)).toBe("Delete Home? The 1 round there loses its location; its archer can give it a new one. This can't be undone.")
    expect(deleteLocationWarning('Sherwood', 5)).toBe("Delete Sherwood? The 5 rounds there (anyone's) lose their location; each archer can give theirs a new one. This can't be undone.")
  })
})
