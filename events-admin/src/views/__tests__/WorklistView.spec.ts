import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { WorklistDismissal, WorklistEntry } from '@/api/dataQuality'
import WorklistView from '@/views/WorklistView.vue'

function entry(overrides: Partial<WorklistEntry> = {}): WorklistEntry {
  return {
    id: 418,
    slug: 'badehaus-die-nerven-2026-09-12',
    title: 'Die Nerven',
    eventDate: '2026-09-12',
    startTime: '20:00:00',
    venueId: 7,
    sourceSlug: 'badehaus',
    flags: [],
    fingerprint: 'abc',
    ...overrides,
  }
}

const dismissal: WorklistDismissal = {
  id: 9,
  issue: 'missingGenre',
  eventId: 418,
  eventSlug: 'badehaus-die-nerven-2026-09-12',
  eventTitle: 'Die Nerven',
  eventDate: '2026-09-12',
  sourceSlug: 'badehaus',
  dismissedBy: 'operator',
  dismissedAt: '2026-10-22T08:00:00Z',
  active: true,
}

let entries: WorklistEntry[] = []
let dismissed = 0
let dismissStatus = 201
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  entries = [entry()]
  dismissed = 0
  dismissStatus = 201
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url.startsWith('/api/admin/data-quality/worklist?')) {
      return json({
        issue: 'missingGenre',
        source: null,
        count: entries.length,
        dismissed,
        entries,
      })
    }
    if (method === 'GET' && url.startsWith('/api/admin/data-quality/worklist/dismissals?')) {
      return json(dismissed ? [dismissal] : [])
    }
    if (method === 'POST' && url === '/api/admin/data-quality/worklist/dismissals') {
      if (dismissStatus !== 201) return json({ detail: 'Event 418 changed' }, dismissStatus)
      entries = []
      dismissed = 1
      return json(dismissal, 201)
    }
    if (method === 'DELETE' && url === '/api/admin/data-quality/worklist/dismissals/9') {
      entries = [entry()]
      dismissed = 0
      return new Response(null, { status: 204 })
    }
    return json({ detail: `unexpected ${method} ${url}` }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

async function mountView(path = '/worklist?issue=missingGenre') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/worklist', component: WorklistView }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(WorklistView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountView>>

function button(wrapper: Wrapper, label: string) {
  const found = wrapper.find(`button[aria-label="${label}"]`)
  expect(found.exists(), `no button "${label}"`).toBe(true)
  return found
}

describe('WorklistView dismissals', () => {
  it('Correct as is posts the fingerprint, drops the row and shows the count', async () => {
    const wrapper = await mountView()

    await button(wrapper, 'Correct as is: Die Nerven').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({
      issue: 'missingGenre',
      eventId: 418,
      fingerprint: 'abc',
    })
    expect(wrapper.text()).toContain('No event fails this check.')
    expect(wrapper.find('[data-testid="dismissed-count"]').text()).toBe('1 correct as is')
  })

  it('offers no dismissal for a row without a fingerprint', async () => {
    entries = [entry({ fingerprint: null })]
    const wrapper = await mountView('/worklist?issue=unreviewedLicence')
    expect(wrapper.find('button[aria-label="Correct as is: Die Nerven"]').exists()).toBe(false)
  })

  it('shows the 409 of a row that changed, and keeps the row', async () => {
    dismissStatus = 409
    const wrapper = await mountView()

    await button(wrapper, 'Correct as is: Die Nerven').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('HTTP 409: Event 418 changed')
    expect(wrapper.text()).toContain('Die Nerven')
  })

  it('lists the dismissals, and Undo puts the row back', async () => {
    dismissed = 1
    entries = []
    const wrapper = await mountView()

    await wrapper.find('[data-testid="dismissed-count"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="dismissal"]')).toHaveLength(1)

    await button(wrapper, 'Undo: Die Nerven').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-testid="dismissal"]')).toHaveLength(0)
    expect(wrapper.find('button[aria-label="Correct as is: Die Nerven"]').exists()).toBe(true)
  })
})
