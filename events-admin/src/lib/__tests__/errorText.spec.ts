import { describe, expect, it } from 'vitest'

import { errorText } from '../errorText'

describe('errorText', () => {
  it('reads the message of an Error', () => {
    expect(errorText(new Error('GET /api/admin/venues: HTTP 503'))).toBe(
      'GET /api/admin/venues: HTTP 503',
    )
  })

  it('reads anything else as a string', () => {
    expect(errorText('refused')).toBe('refused')
    expect(errorText(404)).toBe('404')
    expect(errorText(null)).toBe('null')
  })
})
