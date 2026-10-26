import { describe, expect, it } from 'vitest'

import type { GenreTag } from '@/api/types'
import { activeFamilies } from '@/composables/useFilterLists'

const TAGS = [
  { slug: 'techno', name: 'Techno', family: 'electronic' },
  { slug: 'house', name: 'House', family: 'electronic' },
  { slug: 'rap', name: 'Rap', family: 'hip-hop' },
  { slug: 'untagged', name: 'Untagged' },
] as GenreTag[]

describe('activeFamilies', () => {
  it('takes the URL families as they are, whatever the styles say', () => {
    expect(activeFamilies(['hip-hop'], ['techno'], TAGS)).toEqual(['hip-hop'])
  })

  it('derives one family from a style-only link, however many of its styles it names', () => {
    expect(activeFamilies([], ['techno'], TAGS)).toEqual(['electronic'])
    expect(activeFamilies([], ['techno', 'house'], TAGS)).toEqual(['electronic'])
  })

  it('derives every family of a hand-built link across families (#3980)', () => {
    expect(activeFamilies([], ['techno', 'rap', 'house'], TAGS)).toEqual(['electronic', 'hip-hop'])
  })

  it('derives nothing from an unknown style, a style without a family, or tags not loaded yet', () => {
    expect(activeFamilies([], ['gone', 'untagged'], TAGS)).toEqual([])
    expect(activeFamilies([], ['techno'], [])).toEqual([])
    expect(activeFamilies([], [], TAGS)).toEqual([])
  })
})
