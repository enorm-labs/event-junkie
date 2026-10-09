import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import { inFeatureOrder, PARTY_FEATURES } from '@/lib/partyFeatures'

/** Every party feature has a label in every language; read from disk for the reason `messages.spec.ts` gives. */
describe('PARTY_FEATURES', () => {
  it.each(LOCALES)('has a %s label for every feature', (locale) => {
    const file = resolve(process.cwd(), `src/i18n/messages/${locale}/partyFeature.json`)
    const labels = JSON.parse(readFileSync(file, 'utf8')) as Record<string, string>

    expect(Object.keys(labels).sort()).toEqual([...PARTY_FEATURES].sort())
    for (const slug of PARTY_FEATURES) expect(labels[slug]).toBeTruthy()
  })

  it('lists each feature once', () => {
    expect(new Set(PARTY_FEATURES).size).toBe(PARTY_FEATURES.length)
  })
})

describe('inFeatureOrder', () => {
  it('sorts by the display order and puts an unknown slug last', () => {
    expect(inFeatureOrder(['open-end', 'sober', 'queer', 'flinta-only'])).toEqual([
      'flinta-only',
      'queer',
      'open-end',
      'sober',
    ])
  })

  it('leaves its input alone', () => {
    const slugs = ['open-end', 'queer']
    inFeatureOrder(slugs)
    expect(slugs).toEqual(['open-end', 'queer'])
  })
})
