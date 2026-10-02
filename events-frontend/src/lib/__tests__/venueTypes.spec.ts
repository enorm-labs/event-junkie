import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import { VENUE_TYPES } from '@/lib/venueTypes'

/** Every venue type has a label in every language; read from disk for the reason `messages.spec.ts` gives. */
describe('VENUE_TYPES', () => {
  it.each(LOCALES)('has a %s label for every type', (locale) => {
    const file = resolve(process.cwd(), `src/i18n/messages/${locale}/venueType.json`)
    const labels = JSON.parse(readFileSync(file, 'utf8')) as Record<string, string>

    expect(Object.keys(labels).sort()).toEqual([...VENUE_TYPES].sort())
    for (const slug of VENUE_TYPES) expect(labels[slug]).toBeTruthy()
  })

  it('lists each type once', () => {
    expect(new Set(VENUE_TYPES).size).toBe(VENUE_TYPES.length)
  })
})
