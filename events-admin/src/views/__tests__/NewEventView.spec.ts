import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AdminEvent, createEvent } from '@/api/events'
import type { fetchAllVenues } from '@/api/venues'
import NewEventView from '@/views/NewEventView.vue'

const api = vi.hoisted(() => ({
  createEvent: vi.fn<typeof createEvent>(),
  fetchAllVenues: vi.fn<typeof fetchAllVenues>(),
}))

vi.mock('@/api/events', async (original) => ({
  ...(await original<typeof import('@/api/events')>()),
  createEvent: api.createEvent,
}))
vi.mock('@/api/venues', () => ({ fetchAllVenues: api.fetchAllVenues }))

const body = () => document.body

function field<T extends HTMLInputElement | HTMLSelectElement>(name: string): T {
  return body().querySelector<T>(`[name="${name}"]`)!
}

async function fill(values: Record<string, string>) {
  for (const [name, value] of Object.entries(values)) {
    const element = field(name)
    element.value = value
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input'))
  }
  // The Input component passes its value up from a watcher, one tick later.
  await flushPromises()
}

async function submitWith(button: 'save' | 'saveAndAddAnother') {
  body().querySelector<HTMLButtonElement>(`button[name="${button}"]`)!.click()
  await flushPromises()
}

async function mountView() {
  const wrapper = mount(NewEventView, { attachTo: document.body })
  await flushPromises()
  await fill({
    venueId: '7',
    title: 'Open Decks',
    eventDate: '2026-11-14',
    startTime: '21:00',
    genre: 'House',
    pricePresale: '5',
  })
  return wrapper
}

function saved(overrides: Partial<AdminEvent> = {}): AdminEvent {
  return {
    id: 9001,
    title: 'Open Decks',
    eventDate: '2026-11-14',
    sourceId: 'manual:kulturhaus-x:2026-11-14-open-decks',
    ...overrides,
  } as AdminEvent
}

/**
 * The global fetch, which only `fetchEventsOn` reaches: the venue listing and the POST are mocked
 * above. [sameDay] maps `venueId|date` to the events the importer answers with.
 */
const sameDay = new Map<string, Partial<AdminEvent>[]>()
let checkFails = false
const fetchMock = vi.fn<typeof fetch>(async (input) => {
  const url = new URL(String(input), 'http://admin.test')
  if (url.pathname !== '/api/admin/events') throw new Error(`unexpected fetch ${url}`)
  if (checkFails) {
    return new Response(JSON.stringify({ detail: 'Database down' }), { status: 503 })
  }
  const key = `${url.searchParams.get('venueId')}|${url.searchParams.get('date')}`
  const content = sameDay.get(key) ?? []
  return new Response(JSON.stringify({ content, totalElements: content.length }))
})

function checkedKeys(): string[] {
  return fetchMock.mock.calls.map(([input]) => {
    const url = new URL(String(input), 'http://admin.test')
    return `${url.searchParams.get('venueId')}|${url.searchParams.get('date')}`
  })
}

const lastCheck = () => checkedKeys()[checkedKeys().length - 1]

const sameDayList = () => body().querySelector('[data-testid="same-day"]')
const saveAnywayButton = () => body().querySelector<HTMLButtonElement>('button[name="saveAnyway"]')

beforeEach(() => {
  sameDay.clear()
  checkFails = false
  vi.stubGlobal('fetch', fetchMock)
  api.fetchAllVenues.mockResolvedValue([
    { id: 3, name: 'Another Bar', slug: 'another-bar', closedOn: null },
    { id: 7, name: 'Kulturhaus X', slug: 'kulturhaus-x', closedOn: null },
  ])
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('NewEventView', () => {
  it('POSTs the form as an EventRequest and shows the created id', async () => {
    api.createEvent.mockResolvedValue(saved())
    await mountView()

    await submitWith('save')

    expect(api.createEvent).toHaveBeenCalledOnce()
    expect(api.createEvent.mock.calls[0]![0]).toMatchObject({
      venueId: 7,
      title: 'Open Decks',
      eventDate: '2026-11-14',
      startTime: '21:00',
      genre: 'House',
      pricePresale: 5,
      sourceId: 'manual:kulturhaus-x:2026-11-14-open-decks',
    })
    expect(body().querySelector('[role="status"]')!.textContent).toContain('Created event 9001')
    // A plain save keeps the form as it was.
    expect(field('title').value).toBe('Open Decks')
  })

  it('POSTs the subtitle and the description', async () => {
    api.createEvent.mockResolvedValue(saved())
    await mountView()
    await fill({ subtitle: 'Vinyl only', description: 'Bring your records.\nDecks from 21:00.' })

    await submitWith('save')

    expect(api.createEvent.mock.calls[0]![0]).toMatchObject({
      subtitle: 'Vinyl only',
      description: 'Bring your records.\nDecks from 21:00.',
    })
  })

  it('POSTs an empty subtitle and description as null', async () => {
    api.createEvent.mockResolvedValue(saved())
    await mountView()

    await submitWith('save')

    expect(api.createEvent.mock.calls[0]![0]).toMatchObject({ subtitle: null, description: null })
  })

  it('tells the operator to write the description in their own words', async () => {
    await mountView()

    const hint = body().querySelector(`#${field('description').getAttribute('aria-describedby')}`)
    expect(hint!.textContent).toContain('your own words')
  })

  it('shows the detail of a refused request next to the form', async () => {
    api.createEvent.mockRejectedValue(
      new Error(
        'POST /api/admin/events: HTTP 409 — A record with the same source ID already exists.',
      ),
    )
    await mountView()

    await submitWith('save')

    const alert = body().querySelector('form [role="alert"]')!
    expect(alert.textContent).toContain(
      'HTTP 409 — A record with the same source ID already exists.',
    )
    expect(body().querySelector('[role="status"]')).toBeNull()
  })

  it('"Save and add another" clears every field except the venue and the date', async () => {
    api.createEvent.mockResolvedValue(saved())
    await mountView()
    await fill({
      eventType: 'PARTY',
      ticketUrl: 'https://t.example.org',
      doorsTime: '20:00',
      subtitle: 'Vinyl only',
      description: 'Bring your records.',
    })

    await submitWith('saveAndAddAnother')

    expect(api.createEvent).toHaveBeenCalledOnce()
    expect(field('venueId').value).toBe('7')
    expect(field('eventDate').value).toBe('2026-11-14')
    for (const name of [
      'title',
      'subtitle',
      'description',
      'doorsTime',
      'startTime',
      'genre',
      'ticketUrl',
      'sourceUrl',
      'pricePresale',
      'priceBoxOffice',
    ]) {
      expect({ [name]: field(name).value }).toEqual({ [name]: '' })
    }
    expect(field('eventType').value).toBe('CONCERT')
    expect(field<HTMLInputElement>('free').checked).toBe(false)
    expect(document.activeElement).toBe(field('title'))
  })

  it('keeps the form when a save fails', async () => {
    api.createEvent.mockRejectedValue(new Error('HTTP 400 — Validation failed'))
    await mountView()

    await submitWith('saveAndAddAnother')

    expect(field('title').value).toBe('Open Decks')
    expect(body().textContent).toContain('Validation failed')
  })

  describe('the check for events at the venue on the date', () => {
    it('saves directly when the venue holds no event on the date', async () => {
      api.createEvent.mockResolvedValue(saved())
      await mountView()

      await submitWith('save')

      expect(checkedKeys()).toContain('7|2026-11-14')
      expect(sameDayList()).toBeNull()
      expect(saveAnywayButton()).toBeNull()
      expect(api.createEvent).toHaveBeenCalledOnce()
    })

    it('lists the events on the date and saves only after "Save anyway"', async () => {
      sameDay.set('7|2026-11-14', [
        { id: 51, title: 'Techno Night', startTime: '23:00:00' },
        { id: 52, title: 'Matinee', startTime: null },
      ])
      api.createEvent.mockResolvedValue(saved())
      await mountView()

      // The list shows as soon as the venue and the date are set, before any save.
      expect(sameDayList()!.textContent).toContain('Already on this date at Kulturhaus X:')
      expect(sameDayList()!.textContent).toContain('23:00')
      expect(sameDayList()!.textContent).toContain('Techno Night')
      expect(sameDayList()!.textContent).toContain('no start time')

      await submitWith('save')
      expect(api.createEvent).not.toHaveBeenCalled()

      saveAnywayButton()!.click()
      await flushPromises()

      expect(api.createEvent).toHaveBeenCalledOnce()
      expect(field('title').value).toBe('Open Decks')
      expect(saveAnywayButton()).toBeNull()
    })

    it('"Save anyway" keeps the button that was pressed and re-checks the same night', async () => {
      sameDay.set('7|2026-11-14', [{ id: 51, title: 'Techno Night', startTime: '23:00:00' }])
      api.createEvent.mockResolvedValue(saved())
      await mountView()

      await submitWith('saveAndAddAnother')
      expect(api.createEvent).not.toHaveBeenCalled()
      const checksBefore = fetchMock.mock.calls.length
      saveAnywayButton()!.click()
      await flushPromises()

      expect(api.createEvent).toHaveBeenCalledOnce()
      expect(field('title').value).toBe('')
      expect(field('venueId').value).toBe('7')
      expect(field('eventDate').value).toBe('2026-11-14')
      expect(fetchMock.mock.calls.length).toBeGreaterThan(checksBefore)
      expect(lastCheck()).toBe('7|2026-11-14')
    })

    it('checks again when the venue or the date changes, and drops a pending confirm', async () => {
      sameDay.set('7|2026-11-14', [{ id: 51, title: 'Techno Night', startTime: '23:00:00' }])
      sameDay.set('3|2026-11-15', [{ id: 61, title: 'Quiz', startTime: '19:30:00' }])
      api.createEvent.mockResolvedValue(saved())
      await mountView()
      await submitWith('save')
      expect(saveAnywayButton()).not.toBeNull()

      await fill({ eventDate: '2026-11-15' })
      expect(lastCheck()).toBe('7|2026-11-15')
      expect(sameDayList()).toBeNull()
      expect(saveAnywayButton()).toBeNull()

      await fill({ venueId: '3' })
      expect(lastCheck()).toBe('3|2026-11-15')
      expect(sameDayList()!.textContent).toContain('Already on this date at Another Bar:')
      expect(sameDayList()!.textContent).toContain('Quiz')
      expect(api.createEvent).not.toHaveBeenCalled()
    })

    it('shows a failed check and still saves after "Save anyway"', async () => {
      checkFails = true
      api.createEvent.mockResolvedValue(saved())
      await mountView()

      await submitWith('save')

      expect(body().textContent).toContain('Could not check the events on this date.')
      expect(body().textContent).toContain('HTTP 503 — Database down')
      expect(api.createEvent).not.toHaveBeenCalled()

      saveAnywayButton()!.click()
      await flushPromises()

      expect(api.createEvent).toHaveBeenCalledOnce()
    })
  })
})
