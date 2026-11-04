import { describe, expect, it } from 'vitest'

import { suggestedGenre } from '../genreSuggestion'

describe('suggestedGenre', () => {
  it('names the one family of a venue for a concert, a party and a festival', () => {
    expect(suggestedGenre(['electronic'], 'CONCERT')).toBe('Electronic')
    expect(suggestedGenre(['jazz-blues'], 'PARTY')).toBe('Jazz')
    expect(suggestedGenre(['hip-hop'], 'FESTIVAL')).toBe('Hip Hop')
  })

  it('suggests nothing for a venue with no family or with several', () => {
    expect(suggestedGenre([], 'PARTY')).toBe('')
    expect(suggestedGenre(['electronic', 'pop'], 'PARTY')).toBe('')
  })

  it('suggests nothing for a type that is not music', () => {
    const types = ['COMEDY', 'QUIZ', 'READING', 'SCREENING', 'SHOW', 'OTHER'] as const
    expect(types.map((type) => suggestedGenre(['electronic'], type))).toEqual(types.map(() => ''))
  })

  it('suggests nothing for a family this app does not know', () => {
    expect(suggestedGenre(['new-family'], 'PARTY')).toBe('')
  })
})
