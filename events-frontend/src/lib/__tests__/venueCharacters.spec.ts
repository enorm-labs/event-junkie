import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import { inCharacterOrder, VENUE_CHARACTERS } from '@/lib/venueCharacters'

/** Every character tag has a label in every language; read from disk for the reason `messages.spec.ts` gives. */
describe('VENUE_CHARACTERS', () => {
  it.each(LOCALES)('has a %s label for every tag', (locale) => {
    const file = resolve(process.cwd(), `src/i18n/messages/${locale}/venueCharacter.json`)
    const labels = JSON.parse(readFileSync(file, 'utf8')) as Record<string, string>

    expect(Object.keys(labels).sort()).toEqual([...VENUE_CHARACTERS].sort())
    for (const slug of VENUE_CHARACTERS) expect(labels[slug]).toBeTruthy()
  })

  it('lists each tag once', () => {
    expect(new Set(VENUE_CHARACTERS).size).toBe(VENUE_CHARACTERS.length)
  })
})

describe('inCharacterOrder', () => {
  it('sorts by the display order and puts an unknown slug last', () => {
    const slugs = ['awareness-team', 'sober', 'queer', 'diy-collective']
    expect(inCharacterOrder(slugs, (s) => s)).toEqual([
      'queer',
      'diy-collective',
      'awareness-team',
      'sober',
    ])
  })

  it('leaves its input alone', () => {
    const slugs = ['awareness-team', 'queer']
    inCharacterOrder(slugs, (s) => s)
    expect(slugs).toEqual(['awareness-team', 'queer'])
  })
})
