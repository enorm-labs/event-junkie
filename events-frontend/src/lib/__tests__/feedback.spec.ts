import { describe, expect, it } from 'vitest'

import { feedbackMailto } from '@/lib/feedback'
import { CONTROLLER } from '@/lib/legal'

describe('feedbackMailto', () => {
  it('addresses the role mailbox the privacy notice declares', () => {
    expect(feedbackMailto('Hi')).toBe(`mailto:${CONTROLLER.email}?subject=Hi`)
  })

  it('encodes characters a mail client would read as separators', () => {
    // An event title with `&` or `?` must not end the subject and start a parameter of its own.
    const url = new URL(feedbackMailto('Wrong data: Rock & Roll? #1', 'Event: x\n\nWhat:'))
    expect(url.searchParams.get('subject')).toBe('Wrong data: Rock & Roll? #1')
    expect(url.searchParams.get('body')).toBe('Event: x\n\nWhat:')
    expect(url.hash).toBe('')
  })
})
