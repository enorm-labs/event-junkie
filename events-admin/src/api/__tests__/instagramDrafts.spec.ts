import { describe, expect, it, vi } from 'vitest'

import { emptyNewEventForm } from '@/api/events'
import {
  approveDraft,
  formFromDraft,
  groupByNight,
  type InstagramDraft,
  switchVenueOff,
} from '@/api/instagramDrafts'

function draft(overrides: Partial<InstagramDraft> = {}): InstagramDraft {
  return {
    id: 1,
    venueId: 7,
    venueSlug: 'rso-berlin',
    venueName: 'RSO.Berlin',
    mediaId: '18356757751169892',
    permalink: 'https://www.instagram.com/p/DecMBi9gqGk/',
    postedAt: '2026-10-13T16:00:12Z',
    caption: 'We team up with space.trax for XTRUDE next Saturday, October 17th',
    eventDate: '2026-10-17',
    startTime: '23:00:00',
    title: 'XTRUDE',
    acts: ['space.trax'],
    pricePresale: 15,
    priceBoxOffice: null,
    evidence: 'next Saturday, October 17th',
    confidence: 0.9,
    status: 'PENDING',
    eventId: null,
    createdAt: '2026-10-15T04:30:00Z',
    ...overrides,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('groupByNight', () => {
  it('puts the drafts of one venue and date together, in the order they came', () => {
    const nights = groupByNight([
      draft({ id: 1 }),
      draft({ id: 2, mediaId: 'b' }),
      draft({ id: 3, eventDate: '2026-10-24' }),
      draft({ id: 4, venueId: 8, venueName: 'Prisma' }),
    ])

    expect(nights.map((n) => n.drafts.map((d) => d.id))).toEqual([[1, 2], [3], [4]])
    expect(nights[2]!.venueName).toBe('Prisma')
  })
})

describe('formFromDraft', () => {
  it('fills venue, title, date, start, prices and the post as the source URL', () => {
    const form = formFromDraft(draft(), emptyNewEventForm())

    expect(form).toMatchObject({
      venueId: 7,
      title: 'XTRUDE',
      eventDate: '2026-10-17',
      startTime: '23:00',
      pricePresale: '15.00',
      priceBoxOffice: '',
      sourceUrl: 'https://www.instagram.com/p/DecMBi9gqGk/',
      eventType: 'CONCERT',
    })
  })
})

describe('approveDraft and switchVenueOff', () => {
  it('posts the event id, and a refusal throws with the importer’s detail', async () => {
    const fetchFn = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ status: 'APPROVED', drafts: 2 }))
    await approveDraft(1, 4711, fetchFn)
    expect(fetchFn).toHaveBeenCalledWith(
      '/api/admin/instagram-drafts/1/approve',
      expect.objectContaining({ method: 'POST', body: '{"eventId":4711}' }),
    )

    fetchFn.mockResolvedValueOnce(json({ detail: 'Instagram draft 1 is judged already' }, 409))
    await expect(approveDraft(1, 4711, fetchFn)).rejects.toThrow(
      'HTTP 409 — Instagram draft 1 is judged already',
    )
  })

  it('returns how many drafts a switch-off deleted', async () => {
    const fetchFn = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ slug: 'rso-berlin', draftsDeleted: 3 }))

    await expect(switchVenueOff('rso-berlin', fetchFn)).resolves.toBe(3)
    expect(fetchFn).toHaveBeenCalledWith(
      '/api/admin/instagram/venues/rso-berlin',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})
