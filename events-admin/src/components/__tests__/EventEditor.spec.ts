import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AdminEvent, fetchEvent, unpinField, updateEvent } from '@/api/events'
import EventEditor from '@/components/EventEditor.vue'

const stored: AdminEvent = {
  id: 418,
  venueId: 7,
  title: 'Die Nerven',
  subtitle: null,
  description: null,
  eventType: 'OTHER',
  status: 'SCHEDULED',
  slug: '2026-09-12-badehaus-die-nerven',
  eventDate: '2026-09-12',
  doorsTime: null,
  startTime: '20:00:00',
  imageUrl: null,
  sourceUrl: null,
  sourceId: 'badehaus:die-nerven',
  ticketUrl: null,
  facebookEventUrl: null,
  genre: null,
  genreTags: [],
  pricePresale: null,
  priceBoxOffice: null,
  priceCurrency: 'EUR',
  priceNote: null,
  soldOut: false,
  free: false,
  artists: [],
  promoterIds: [],
  pinnedFields: ['title'],
}

const api = vi.hoisted(() => ({
  fetchEvent: vi.fn<typeof fetchEvent>(),
  updateEvent: vi.fn<typeof updateEvent>(),
  unpinField: vi.fn<typeof unpinField>(),
}))

vi.mock('@/api/events', async (original) => ({
  ...(await original<typeof import('@/api/events')>()),
  ...api,
}))

async function mountEditor() {
  api.fetchEvent.mockResolvedValue(stored)
  const wrapper = mount(EventEditor, { props: { eventId: 418 }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const body = () => document.body

afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('EventEditor', () => {
  it('saves the edited type and genre over the stored event', async () => {
    api.updateEvent.mockResolvedValue({ ...stored, eventType: 'CONCERT', genre: 'Punk' })
    const wrapper = await mountEditor()

    const select = body().querySelector<HTMLSelectElement>('select[name="eventType"]')!
    select.value = 'CONCERT'
    select.dispatchEvent(new Event('change'))
    const genre = body().querySelector<HTMLInputElement>('input[name="genre"]')!
    genre.value = 'Punk'
    genre.dispatchEvent(new Event('input'))
    // The Input component passes its value up from a watcher, one tick later.
    await flushPromises()
    body().querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(api.updateEvent).toHaveBeenCalledOnce()
    const [id, request] = api.updateEvent.mock.calls[0]!
    expect(id).toBe(418)
    expect(request).toMatchObject({ title: 'Die Nerven', eventType: 'CONCERT', genre: 'Punk' })
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('unpins a field and reads the pins back', async () => {
    api.unpinField.mockResolvedValue(undefined)
    await mountEditor()
    api.fetchEvent.mockResolvedValue({ ...stored, pinnedFields: [] })

    body().querySelector<HTMLButtonElement>('button[aria-label="Unpin title"]')!.click()
    await flushPromises()

    expect(api.unpinField).toHaveBeenCalledWith(418, 'title')
    expect(api.fetchEvent).toHaveBeenCalledTimes(2)
    expect(body().textContent).toContain('None.')
  })
})
