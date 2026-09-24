import { describe, expect, it } from 'vitest'

import type { EventDetail } from '@/api/types'
import { descriptionFor } from '@/lib/description'

/**
 * Which text a page shows, and what language it declares for it.
 *
 * The failure this guards is not a blank page: it is a page that shows German prose and tells a
 * screen reader, a crawler and the browser's translate feature that it is English. That was true
 * of every event page before ADR-026, and it is invisible without an assertion.
 */
const event = (fields: Partial<EventDetail> = {}): EventDetail =>
  ({
    slug: '2026-06-12-lido-test-act',
    title: 'Test Act',
    ...fields,
  }) as EventDetail

describe('descriptionFor', () => {
  it('shows the original when it is already in the visitor’s language', () => {
    const chosen = descriptionFor(
      event({ description: 'Ein Abend mit Aussicht', descriptionLanguage: 'de' }),
      'de',
    )

    expect(chosen).toEqual({
      text: 'Ein Abend mit Aussicht',
      lang: 'de',
      machine: false,
      side: 'original',
    })
  })

  it('prefers the other-language text when the visitor reads that language', () => {
    const chosen = descriptionFor(
      event({
        description: 'Ein Abend mit Aussicht',
        descriptionLanguage: 'de',
        descriptionAlt: 'An evening with a view',
        descriptionAltLanguage: 'en',
        descriptionAltOrigin: 'PUBLISHER',
      }),
      'en',
    )

    expect(chosen).toEqual({
      text: 'An evening with a view',
      lang: 'en',
      machine: false,
      side: 'alt',
    })
  })

  // The disclosure the page shows hangs off this flag, and ADR-026 makes it non-optional.
  it('reports a machine translation as one', () => {
    const chosen = descriptionFor(
      event({
        description: 'Ein Abend mit Aussicht',
        descriptionLanguage: 'de',
        descriptionAlt: 'An evening with a view',
        descriptionAltLanguage: 'en',
        descriptionAltOrigin: 'MACHINE',
      }),
      'en',
    )

    expect(chosen).toEqual({
      text: 'An evening with a view',
      lang: 'en',
      machine: true,
      side: 'alt',
    })
  })

  // A description a visitor cannot read still says who is playing. It stays, marked.
  it('falls back to the original in its own language', () => {
    const chosen = descriptionFor(
      event({ description: 'Ein Abend mit Aussicht', descriptionLanguage: 'de' }),
      'en',
    )

    expect(chosen).toEqual({
      text: 'Ein Abend mit Aussicht',
      lang: 'de',
      machine: false,
      side: 'original',
    })
  })

  // An unclassified text claims nothing rather than claiming the page's locale.
  it('declares no language for an unclassified description', () => {
    const chosen = descriptionFor(event({ description: 'Doors 19:30' }), 'en')

    expect(chosen).toEqual({ text: 'Doors 19:30', lang: null, machine: false, side: 'original' })
  })

  it('returns null when the event has no description', () => {
    expect(descriptionFor(event(), 'en')).toBeNull()
    expect(descriptionFor(event({ descriptionWithheld: true }), 'de')).toBeNull()
  })

  // A language outside the two the site publishes is data we cannot act on, so it is not a `lang`.
  it('ignores a language it does not publish', () => {
    const chosen = descriptionFor(
      event({ description: 'Un dúo argentino', descriptionLanguage: 'es' }),
      'en',
    )

    expect(chosen).toEqual({
      text: 'Un dúo argentino',
      lang: null,
      machine: false,
      side: 'original',
    })
  })
})

describe('descriptionFor, on a venue', () => {
  const venue = {
    description: 'A former cinema on the canal.',
    descriptionLanguage: 'en',
    descriptionAlt: 'Ein früheres Kino am Kanal.',
    descriptionAltLanguage: 'de',
  }

  it('gives the visitor the text in their own language', () => {
    expect(descriptionFor(venue, 'de')).toEqual({
      text: 'Ein früheres Kino am Kanal.',
      lang: 'de',
      machine: false,
      side: 'alt',
    })
  })

  it('never marks a venue text as machine-made, because it carries no origin', () => {
    expect(descriptionFor(venue, 'de')!.machine).toBe(false)
    expect(descriptionFor(venue, 'en')!.machine).toBe(false)
  })

  it('falls back to the English original when there is no German', () => {
    const english = { description: 'Only English here.', descriptionLanguage: 'en' }
    expect(descriptionFor(english, 'de')).toEqual({
      text: 'Only English here.',
      lang: 'en',
      machine: false,
      side: 'original',
    })
  })
})
