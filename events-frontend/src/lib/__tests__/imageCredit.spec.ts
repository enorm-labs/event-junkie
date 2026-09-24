import { describe, expect, it } from 'vitest'

import { imageCredit, textCredit } from '@/lib/imageCredit'

/**
 * Whether an image may be shown, and what has to be shown with it.
 *
 * A CC BY image rendered without its credit is a licence breach rather than a cosmetic gap, so a
 * half-filled row must produce nothing at all. The API refuses to store one; this is the second
 * line, against a row that predates the rule or arrives from a future source.
 */
describe('imageCredit', () => {
  const complete = {
    imageUrl: 'https://upload.wikimedia.org/example.jpg',
    imageAttribution: 'Photographer Name, via Wikimedia Commons',
    imageLicenceId: 'CC-BY-SA-4.0',
    imageSourceUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
  }

  it('credits the author and links the licence deed', () => {
    expect(imageCredit(complete)).toEqual({
      attribution: 'Photographer Name, via Wikimedia Commons',
      sourceUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
      licenceLabel: 'CC BY-SA 4.0',
      licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
    })
  })

  it('gives a public-domain image a label and no deed to link', () => {
    const credit = imageCredit({ ...complete, imageLicenceId: 'PD' })

    expect(credit?.licenceLabel).toBe('Public domain')
    expect(credit?.licenceUrl).toBeNull()
  })

  it('links a German port to its own deed, not the international one', () => {
    // Two of the confirmed venue images are ported licences (#1277). They are separate licences
    // with their own deeds, so a suffix-stripping guess would send the reader to the wrong text.
    const credit = imageCredit({ ...complete, imageLicenceId: 'CC-BY-SA-3.0-DE' })

    expect(credit?.licenceLabel).toBe('CC BY-SA 3.0 DE')
    expect(credit?.licenceUrl).toBe('https://creativecommons.org/licenses/by-sa/3.0/de/')
  })

  it('names the Free Art License rather than printing its identifier', () => {
    const credit = imageCredit({ ...complete, imageLicenceId: 'LAL-1.3' })

    expect(credit?.licenceLabel).toBe('Free Art License 1.3')
    expect(credit?.licenceUrl).toBe('https://artlibre.org/licence/lal/en/')
  })

  it('links the Commons attribution-only template, which is its own licence statement', () => {
    const credit = imageCredit({ ...complete, imageLicenceId: 'LicenseRef-Commons-Attribution' })

    expect(credit?.licenceLabel).toBe('Attribution only')
    expect(credit?.licenceUrl).toBe('https://commons.wikimedia.org/wiki/Template:Attribution')
  })

  it('shows an unknown identifier as it stands rather than guessing a deed', () => {
    const credit = imageCredit({ ...complete, imageLicenceId: 'CC-BY-NC-4.0' })

    expect(credit?.licenceLabel).toBe('CC-BY-NC-4.0')
    expect(credit?.licenceUrl).toBeNull()
  })

  it.each(['imageAttribution', 'imageLicenceId', 'imageSourceUrl'] as const)(
    'renders nothing when %s is missing',
    (field) => {
      expect(imageCredit({ ...complete, [field]: null })).toBeNull()
    },
  )

  it('renders nothing for a row with no image', () => {
    expect(imageCredit({ ...complete, imageUrl: null })).toBeNull()
    expect(imageCredit(null)).toBeNull()
  })
})

describe('textCredit', () => {
  const lead = {
    description: 'Giant Rooks ist eine deutsche Indie-Pop-Band aus Hamm, die 2014 gegründet wurde.',
    descriptionAttribution: 'Wikipedia',
    descriptionLicenceId: 'CC-BY-SA-4.0',
    descriptionSourceUrl: 'https://de.wikipedia.org/wiki/Giant_Rooks',
  }

  it('credits a Wikipedia lead with the article and the licence deed', () => {
    expect(textCredit(lead)).toEqual({
      attribution: 'Wikipedia',
      sourceUrl: 'https://de.wikipedia.org/wiki/Giant_Rooks',
      licenceLabel: 'CC BY-SA 4.0',
      licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
    })
  })

  it('owes nothing for a text a venue or a person wrote, or for a credit with no text', () => {
    expect(textCredit({ description: 'Punk aus Ipswich.' })).toBeNull()
    expect(textCredit({ ...lead, description: null })).toBeNull()
    expect(textCredit(null)).toBeNull()
  })
})
