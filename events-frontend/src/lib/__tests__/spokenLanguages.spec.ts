import { describe, expect, it } from 'vitest'
import { languageFilterApplies } from '@/lib/spokenLanguages'

describe('languageFilterApplies', () => {
  it('holds when every selected type carries a language', () => {
    expect(languageFilterApplies(['COMEDY'])).toBe(true)
    expect(languageFilterApplies(['comedy', 'READING', 'QUIZ', 'SHOW'])).toBe(true)
  })

  it('does not hold with no type, or with one type that never says', () => {
    expect(languageFilterApplies([])).toBe(false)
    expect(languageFilterApplies(['CONCERT'])).toBe(false)
    expect(languageFilterApplies(['COMEDY', 'PARTY'])).toBe(false)
  })
})
