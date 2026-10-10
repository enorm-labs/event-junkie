import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AdminEvent, fetchEvent, unpinField, updateEvent } from '@/api/events'
import type { fetchNames, NamedRow, searchByName } from '@/api/names'
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

const known: Record<'artists' | 'promoters', NamedRow[]> = {
  artists: [
    { id: 3, name: 'Die Nerven', slug: 'die-nerven' },
    { id: 12, name: 'The Adicts', slug: 'the-adicts' },
  ],
  promoters: [
    { id: 9, name: 'Badehaus', slug: 'badehaus' },
    { id: 7, name: '36 Concerts', slug: '36-concerts' },
  ],
}

const names = vi.hoisted(() => ({
  fetchNames: vi.fn<typeof fetchNames>(),
  searchByName: vi.fn<typeof searchByName>(),
}))

vi.mock('@/api/names', async (original) => ({
  ...(await original<typeof import('@/api/names')>()),
  ...names,
}))

async function mountEditor(event: AdminEvent = stored) {
  names.fetchNames.mockImplementation(
    async (kind, ids) =>
      new Map(ids.map((id) => [id, known[kind].find((row) => row.id === id) ?? null])),
  )
  names.searchByName.mockImplementation(async (kind, term) => {
    const rows = known[kind].filter((row) => row.name.toLowerCase().includes(term.toLowerCase()))
    return { rows, total: rows.length }
  })
  api.fetchEvent.mockResolvedValue(event)
  const wrapper = mount(EventEditor, { props: { eventId: 418 }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const body = () => document.body

/** Types [term] into the picker [id] and waits out its debounce. */
async function search(id: string, term: string) {
  const input = body().querySelector<HTMLInputElement>(`input#${id}`)!
  input.value = term
  input.dispatchEvent(new Event('input'))
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 300))
  await flushPromises()
}

function click(label: string) {
  const button = body().querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)
  if (!button) throw new Error(`No button "${label}"`)
  button.click()
}

async function submit() {
  body().querySelector('form')!.dispatchEvent(new Event('submit'))
  await flushPromises()
}

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

  describe('subtitle and description', () => {
    const described: AdminEvent = {
      ...stored,
      subtitle: 'Wüst Tour 2026',
      description: 'Noise rock from Stuttgart.\n',
    }

    /** Types [value] into the field [name], an input or a textarea. */
    async function type(name: string, value: string) {
      const field = body().querySelector<HTMLInputElement | HTMLTextAreaElement>(
        `[name="${name}"]`,
      )!
      field.value = value
      field.dispatchEvent(new Event('input'))
      await flushPromises()
    }

    it('shows the stored subtitle and description', async () => {
      await mountEditor(described)

      expect(body().querySelector<HTMLInputElement>('input[name="subtitle"]')!.value).toBe(
        'Wüst Tour 2026',
      )
      expect(body().querySelector<HTMLTextAreaElement>('textarea[name="description"]')!.value).toBe(
        'Noise rock from Stuttgart.\n',
      )
    })

    it('saves a set subtitle and description in the PUT body', async () => {
      api.updateEvent.mockResolvedValue(described)
      await mountEditor()

      await type('subtitle', 'Wüst Tour 2026')
      await type('description', 'Ein Abend mit Die Nerven aus Stuttgart.')
      await submit()

      expect(api.updateEvent.mock.calls[0]![1]).toMatchObject({
        title: 'Die Nerven',
        subtitle: 'Wüst Tour 2026',
        description: 'Ein Abend mit Die Nerven aus Stuttgart.',
      })
    })

    it('saves a cleared subtitle and description as null', async () => {
      api.updateEvent.mockResolvedValue(stored)
      await mountEditor(described)

      await type('subtitle', '')
      await type('description', '  ')
      await submit()

      expect(api.updateEvent.mock.calls[0]![1]).toMatchObject({ subtitle: null, description: null })
    })

    it('sends an untouched subtitle and description back as they were read', async () => {
      api.updateEvent.mockResolvedValue(described)
      await mountEditor(described)

      await submit()

      expect(api.updateEvent.mock.calls[0]![1]).toMatchObject({
        subtitle: 'Wüst Tour 2026',
        description: 'Noise rock from Stuttgart.\n',
      })
    })

    it('tells the operator to write the description in their own words', async () => {
      await mountEditor()

      expect(body().querySelector('#edit-description-hint')!.textContent).toContain(
        'your own words',
      )
    })
  })

  describe('lineup and promoters', () => {
    const booked: AdminEvent = {
      ...stored,
      artists: [{ artistId: 3, role: 'HEADLINER', billingOrder: 0, stage: null }],
      promoterIds: [9],
    }

    it('shows the names of the stored lineup and promoters', async () => {
      await mountEditor(booked)

      const rows = [...body().querySelectorAll('[data-testid="lineup-row"]')]
      expect(rows.map((row) => row.textContent)).toEqual([expect.stringContaining('Die Nerven')])
      expect(body().querySelector('[data-testid="promoter-row"]')!.textContent).toContain(
        'Badehaus',
      )
    })

    it('saves an added artist, a changed role and a swapped promoter in the PUT body', async () => {
      api.updateEvent.mockResolvedValue(booked)
      await mountEditor(booked)

      await search('add-artist', 'adicts')
      expect(names.searchByName).toHaveBeenCalledWith('artists', 'adicts')
      click('Add The Adicts')
      await flushPromises()
      const role = body().querySelector<HTMLSelectElement>(
        'select[aria-label="Role of Die Nerven"]',
      )!
      role.value = 'DJ'
      role.dispatchEvent(new Event('change'))
      click('Remove Badehaus')
      await search('add-promoter', '36')
      click('Add 36 Concerts')
      await flushPromises()
      await submit()

      const [, request] = api.updateEvent.mock.calls[0]!
      expect(request.artists).toEqual([
        { artistId: 3, role: 'DJ', billingOrder: 0, stage: null },
        { artistId: 12, role: 'SUPPORT', billingOrder: 1, stage: null },
      ])
      expect(request.promoterIds).toEqual([7])
    })

    it('saves a removed artist as an empty lineup, and adds the first act as the headliner', async () => {
      // The answer carries what was saved, and the editor shows it.
      api.updateEvent.mockImplementation(async (_id, request) => ({
        ...booked,
        artists: request.artists,
        promoterIds: request.promoterIds,
      }))
      await mountEditor(booked)

      click('Remove Die Nerven')
      await flushPromises()
      expect(body().textContent).toContain('No artists.')
      await submit()
      expect(api.updateEvent.mock.calls[0]![1].artists).toEqual([])

      await search('add-artist', 'adicts')
      click('Add The Adicts')
      await submit()
      expect(api.updateEvent.mock.calls[1]![1].artists).toEqual([
        { artistId: 12, role: 'HEADLINER', billingOrder: 0, stage: null },
      ])
    })

    it('offers an artist already on the bill as added, not twice', async () => {
      await mountEditor(booked)

      await search('add-artist', 'nerven')

      const button = body().querySelector<HTMLButtonElement>('button[aria-label="Add Die Nerven"]')!
      expect(button.disabled).toBe(true)
      expect(button.textContent).toContain('Added')
    })

    it('says so when no name matches', async () => {
      await mountEditor(booked)

      await search('add-promoter', 'Bellmer')

      expect(body().textContent).toContain('No promoters match “Bellmer”.')
    })
  })
})
