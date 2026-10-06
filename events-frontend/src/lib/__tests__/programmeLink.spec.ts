import { describe, expect, it } from 'vitest'
import { programmeLink } from '@/lib/programmeLink'

describe('programmeLink', () => {
  it('reads a Resident Advisor page, with or without a language subdomain', () => {
    expect(programmeLink('https://ra.co/clubs/185172')).toEqual({ kind: 'resident-advisor' })
    expect(programmeLink('https://de.ra.co/clubs/217616')).toEqual({ kind: 'resident-advisor' })
    expect(programmeLink('https://ra.co/promoters/181571')).toEqual({ kind: 'resident-advisor' })
  })

  it('names a ticket platform', () => {
    expect(programmeLink('https://www.eventbrite.de/o/ashawo-123')).toEqual({
      kind: 'platform',
      platform: 'Eventbrite',
    })
    expect(programmeLink('https://dice.fm/venue/prachtwerk-abc')).toEqual({
      kind: 'platform',
      platform: 'DICE',
    })
  })

  it("takes anything else for the venue's own page", () => {
    expect(programmeLink('https://www.orangerie-nk.de/?lang=de#programm')).toEqual({ kind: 'own' })
    expect(programmeLink('https://extra.co/clubs')).toEqual({ kind: 'own' })
    expect(programmeLink('not a url')).toEqual({ kind: 'own' })
  })
})
