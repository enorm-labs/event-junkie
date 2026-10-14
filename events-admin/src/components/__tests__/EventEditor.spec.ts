import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { checkEvent, ClassificationRun } from '@/api/dataQuality'
import type {
  AdminEvent,
  EventFeatures,
  fetchEvent,
  fetchEventFeatures,
  setEventFeatures,
  unpinField,
  updateEvent,
} from '@/api/events'
import { PARTY_FEATURES } from '@/api/events'
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
  fetchEventFeatures: vi.fn<typeof fetchEventFeatures>(),
  setEventFeatures: vi.fn<typeof setEventFeatures>(),
}))

const noFeatures: EventFeatures = { eventId: 418, features: [], uncertain: [], pinned: false }

vi.mock('@/api/events', async (original) => ({
  ...(await original<typeof import('@/api/events')>()),
  ...api,
}))

const quality = vi.hoisted(() => ({ checkEvent: vi.fn<typeof checkEvent>() }))

vi.mock('@/api/dataQuality', async (original) => ({
  ...(await original<typeof import('@/api/dataQuality')>()),
  ...quality,
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

async function mountEditor(event: AdminEvent = stored, features: EventFeatures = noFeatures) {
  names.fetchNames.mockImplementation(
    async (kind, ids) =>
      new Map(ids.map((id) => [id, known[kind].find((row) => row.id === id) ?? null])),
  )
  names.searchByName.mockImplementation(async (kind, term) => {
    const rows = known[kind].filter((row) => row.name.toLowerCase().includes(term.toLowerCase()))
    return { rows, total: rows.length }
  })
  api.fetchEvent.mockResolvedValue(event)
  api.fetchEventFeatures.mockResolvedValue(features)
  const wrapper = mount(EventEditor, {
    props: { eventId: 418 },
    attachTo: document.body,
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
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
  describe('kind of night', () => {
    const party: EventFeatures = {
      eventId: 418,
      features: [{ feature: 'queer', phrase: 'Queer party' }],
      uncertain: [{ feature: 'flinta-only', phrase: '…A night with FLINTA* DJs.' }],
      pinned: false,
    }

    function checkbox(slug: string) {
      return body().querySelector<HTMLInputElement>(`input#edit-feature-${slug}`)!
    }

    /** Clicks the box of [slug], then lets the model update before the next click reads it. */
    async function toggle(slug: string) {
      checkbox(slug).click()
      await flushPromises()
    }

    it('ticks the stored features and shows an uncertain cue next to its feature', async () => {
      await mountEditor(stored, party)

      expect(body().querySelectorAll('[data-testid="feature-row"]')).toHaveLength(
        PARTY_FEATURES.length,
      )
      expect(checkbox('queer').checked).toBe(true)
      expect(checkbox('flinta-only').checked).toBe(false)
      const cue = body().querySelector('[data-testid="feature-cue"]')!
      expect(cue.closest('[data-testid="feature-row"]')!.textContent).toContain('FLINTA* only')
      expect(cue.textContent).toContain('…A night with FLINTA* DJs.')
    })

    it('saves a changed set through the features route, before the event', async () => {
      api.setEventFeatures.mockResolvedValue({
        ...party,
        features: [{ feature: 'flinta-only', phrase: 'set by hand' }],
        uncertain: [],
        pinned: true,
      })
      api.updateEvent.mockResolvedValue({ ...stored, pinnedFields: ['title', 'features'] })
      await mountEditor(stored, party)

      await toggle('open-end')
      await toggle('flinta-only')
      await toggle('queer')
      await submit()

      expect(api.setEventFeatures).toHaveBeenCalledExactlyOnceWith(418, ['flinta-only', 'open-end'])
      expect(api.setEventFeatures.mock.invocationCallOrder[0]).toBeLessThan(
        api.updateEvent.mock.invocationCallOrder[0]!,
      )
      expect(body().querySelector('[data-testid="feature-cue"]')).toBeNull()
      expect(body().querySelector('button[aria-label="Unpin features"]')).not.toBeNull()
    })

    it('does not call the features route when the set is unchanged', async () => {
      api.updateEvent.mockResolvedValue(stored)
      await mountEditor(stored, party)

      // Off and on again: the same set.
      await toggle('queer')
      await toggle('queer')
      await submit()

      expect(api.updateEvent).toHaveBeenCalledOnce()
      expect(api.setEventFeatures).not.toHaveBeenCalled()
    })

    it('keeps the editor usable when the features cannot be read', async () => {
      api.updateEvent.mockResolvedValue(stored)
      names.fetchNames.mockResolvedValue(new Map())
      api.fetchEvent.mockResolvedValue(stored)
      api.fetchEventFeatures.mockRejectedValue(
        new Error('GET /api/admin/events/418/features: HTTP 404'),
      )
      mount(EventEditor, { props: { eventId: 418 }, attachTo: document.body })
      await flushPromises()

      expect(body().textContent).toContain('HTTP 404')
      expect(body().querySelectorAll('[data-testid="feature-row"]')).toHaveLength(0)
      await submit()
      expect(api.updateEvent).toHaveBeenCalledOnce()
      expect(api.setEventFeatures).not.toHaveBeenCalled()
    })
  })

  describe('Check with AI', () => {
    const run = (overrides: Partial<ClassificationRun>): ClassificationRun => ({
      candidates: 1,
      checked: 0,
      unchanged: 0,
      failed: 0,
      overCap: 0,
      overBudget: 0,
      suggested: {},
      ...overrides,
    })

    const buttonNamed = (label: string) =>
      [...body().querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent?.trim() === label,
      )

    async function check(answer: ClassificationRun | Error) {
      if (answer instanceof Error) quality.checkEvent.mockRejectedValue(answer)
      else quality.checkEvent.mockResolvedValue(answer)
      const wrapper = await mountEditor()
      buttonNamed('Check with AI')!.click()
      await flushPromises()
      expect(quality.checkEvent).not.toHaveBeenCalled()
      expect(body().querySelector('[data-testid="check-confirm"]')?.textContent).toContain(
        'token budget',
      )
      buttonNamed('Check')!.click()
      await flushPromises()
      return wrapper
    }

    const outcome = () => body().querySelector('[data-testid="check-outcome"]')?.textContent

    it.each([
      [
        'checked, with suggestions',
        run({ checked: 1, suggested: { startTime: 1, eventType: 1 } }),
        'Checked. New suggestions for: startTime, eventType.',
      ],
      ['checked, without', run({ checked: 1 }), 'Checked. No field differs from the stored value.'],
      ['unchanged', run({ unchanged: 1 }), 'Skipped: the title, subtitle and description'],
      ['failed', run({ failed: 1 }), 'The model gave no answer.'],
      ['overBudget', run({ overBudget: 1 }), 'Not sent: the month’s token budget is spent.'],
    ])('confirms, checks the event without force and reports %s', async (_, answer, text) => {
      const wrapper = await check(answer)

      expect(quality.checkEvent).toHaveBeenCalledExactlyOnceWith(418)
      expect(outcome()).toContain(text)
      expect(wrapper.getComponent(RouterLinkStub).props('to')).toBe('/suggestions')
      expect(api.updateEvent).not.toHaveBeenCalled()
    })

    it('Cancel calls nothing', async () => {
      await mountEditor()
      buttonNamed('Check with AI')!.click()
      await flushPromises()
      buttonNamed('Cancel')!.click()
      await flushPromises()

      expect(quality.checkEvent).not.toHaveBeenCalled()
      expect(body().querySelector('[data-testid="check-confirm"]')).toBeNull()
      expect(outcome()).toBeUndefined()
    })

    it('shows the message of a 409', async () => {
      const message =
        'POST /api/admin/data-quality/check/event/418: HTTP 409: The event-classification check is off'
      await check(new Error(message))

      expect(body().querySelector('[role="alert"]')?.textContent).toBe(message)
      expect(outcome()).toBeUndefined()
    })

    it('closes the editor from the Suggested fixes link', async () => {
      const wrapper = await check(run({ checked: 1 }))

      await wrapper.getComponent(RouterLinkStub).trigger('click')

      expect(wrapper.emitted('close')).toHaveLength(1)
    })
  })
})
