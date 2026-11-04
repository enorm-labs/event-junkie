import { describe, expect, it } from 'vitest'

import { canonicalPath } from '../meta.ts'

describe('canonicalPath', () => {
  it("names the entity's own slug when the request used a former one", () => {
    expect(canonicalPath('artists', '/artists/dj-testbild', { slug: 'testbild' })).toBe(
      '/artists/testbild',
    )
  })

  it('keeps the requested path when the slugs agree or the entity has no slug', () => {
    expect(canonicalPath('artists', '/artists/testbild', { slug: 'testbild' })).toBe(
      '/artists/testbild',
    )
    expect(canonicalPath('venues', '/venues/lido', {})).toBe('/venues/lido')
  })
})
