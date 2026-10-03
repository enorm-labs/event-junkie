import { describe, expect, it } from 'vitest'
import { Disc3, Drama, Guitar, Laugh, Music, PartyPopper, Speaker } from '@lucide/vue'

import { GENRE_FAMILIES } from '@/lib/genreFamilies'
import { posterArt } from '@/lib/posterArt'

describe('posterArt', () => {
  it('draws a music event by its first known genre family', () => {
    expect(posterArt('CONCERT', ['unknown', 'rock', 'electronic'])).toEqual({
      icon: Guitar,
      ground: 'rock',
    })
    expect(posterArt('PARTY', ['electronic'])).toEqual({ icon: Disc3, ground: 'electronic' })
  })

  it('gives a concert without a known family its own ground', () => {
    expect(posterArt('CONCERT', [])).toEqual({ icon: Music, ground: 'concert' })
    expect(posterArt('PARTY', null)).toEqual({ icon: Speaker, ground: 'electronic' })
  })

  it('keeps a non-music type whatever genre it is tagged with', () => {
    expect(posterArt('COMEDY', ['rock'])).toEqual({ icon: Laugh, ground: 'stage' })
    // A city festival is days of acts, not a campsite.
    expect(posterArt('FESTIVAL', ['electronic'])).toEqual({ icon: PartyPopper, ground: 'festival' })
  })

  it('draws a venue by its programme, then by its venue type', () => {
    expect(posterArt(undefined, ['rock'], ['theatre']).ground).toBe('rock')
    expect(posterArt(undefined, [], ['unknown', 'theatre'])).toEqual({
      icon: Drama,
      ground: 'stage',
    })
    expect(posterArt(undefined, [], []).ground).toBe('other')
  })

  it('ignores venue types for an event', () => {
    expect(posterArt('CONCERT', [], ['theatre']).ground).toBe('concert')
  })

  // A family added on the backend reaches the filter's list first; it needs a drawing too.
  it.each(GENRE_FAMILIES)('has a drawing for the %s family', (family) => {
    expect(posterArt('CONCERT', [family]).ground).not.toBe('concert')
  })
})
