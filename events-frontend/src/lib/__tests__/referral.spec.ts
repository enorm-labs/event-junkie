import { describe, expect, it } from 'vitest'

import { withReferral } from '@/lib/referral'

const TAG = 'utm_source=event-junkie.de&utm_medium=referral'

describe('withReferral', () => {
  it('tags a plain URL', () => {
    expect(withReferral('https://katerblau.de/')).toBe(`https://katerblau.de/?${TAG}`)
  })

  it('appends to an existing query and keeps the fragment', () => {
    expect(withReferral('https://tickets.example/e?id=42#seats')).toBe(
      `https://tickets.example/e?id=42&${TAG}#seats`,
    )
  })

  it('keeps the encoding of the existing query', () => {
    expect(withReferral('https://shop.example/s?q=a%20b')).toBe(
      `https://shop.example/s?q=a%20b&${TAG}`,
    )
  })

  it('leaves a URL that already carries a utm_ parameter unchanged', () => {
    const tagged = 'https://shop.example/e?utm_campaign=spring'
    expect(withReferral(tagged)).toBe(tagged)
    expect(withReferral('https://shop.example/e?UTM_Source=ra')).toBe(
      'https://shop.example/e?UTM_Source=ra',
    )
  })

  it.each(['mailto:booking@example.de', 'tel:+49301234567', 'not a url', ''])(
    'leaves %j unchanged',
    (href) => {
      expect(withReferral(href)).toBe(href)
    },
  )
})
