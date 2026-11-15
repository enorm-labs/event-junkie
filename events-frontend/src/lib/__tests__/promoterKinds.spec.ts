import { describe, expect, it } from 'vitest'
import { promoterKindKey } from '@/lib/promoterKinds'

describe('promoterKindKey', () => {
  it.each(['collective', 'label', 'party'] as const)('names a %s', (kind) => {
    expect(promoterKindKey(kind)).toBe(`detail.promoter.type.${kind}`)
  })

  it.each(['promoter', 'media', 'artist', 'sponsor', 'venue', null, undefined] as const)(
    'names nothing for %s',
    (kind) => {
      expect(promoterKindKey(kind)).toBeNull()
    },
  )
})
