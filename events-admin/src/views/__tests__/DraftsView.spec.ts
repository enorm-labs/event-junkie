import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { InstagramDraft } from '@/api/instagramDrafts'
import DraftsView from '@/views/DraftsView.vue'

function draft(overrides: Partial<InstagramDraft>): InstagramDraft {
  return {
    id: 1,
    venueId: 7,
    venueSlug: 'rso-berlin',
    venueName: 'RSO.Berlin',
    mediaId: 'a',
    permalink: 'https://www.instagram.com/p/a/',
    postedAt: '2026-10-13T16:00:12Z',
    caption: 'We team up with space.trax for XTRUDE next Saturday, October 17th',
    eventDate: '2026-10-17',
    startTime: null,
    title: 'XTRUDE',
    acts: ['space.trax'],
    pricePresale: null,
    priceBoxOffice: null,
    evidence: 'next Saturday, October 17th',
    confidence: 0.9,
    status: 'PENDING',
    eventId: null,
    createdAt: '2026-10-15T04:30:00Z',
    ...overrides,
  }
}

let listed: InstagramDraft[] = []
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  listed = [
    draft({ id: 1 }),
    draft({ id: 2, mediaId: 'b', permalink: 'https://www.instagram.com/p/b/' }),
    draft({
      id: 3,
      venueId: 8,
      venueName: 'Prisma',
      eventDate: '2026-10-18',
      title: 'Prisma Jam',
      acts: [],
    }),
  ]
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url.startsWith('/api/admin/instagram-drafts?')) {
      return json({ content: listed, totalElements: listed.length, totalPages: 1 })
    }
    if (method === 'GET' && url === '/api/admin/instagram/venues') {
      return json([{ slug: 'rso-berlin', name: 'RSO.Berlin', handle: 'rso.berlin' }])
    }
    if (method === 'POST' && url === '/api/admin/instagram-drafts/3/reject')
      return json({ status: 'REJECTED', drafts: 1 })
    if (method === 'DELETE' && url === '/api/admin/instagram/venues/rso-berlin') {
      return json({ slug: 'rso-berlin', draftsDeleted: 2 })
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

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/drafts', name: 'drafts', component: DraftsView },
      { path: '/events/new', name: 'new-event', component: { template: '<p>new</p>' } },
    ],
  })
  await router.push('/drafts')
  await router.isReady()
  const wrapper = mount(DraftsView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

function methods() {
  return fetchMock.mock.calls.map(([input, init]) => `${init?.method ?? 'GET'} ${String(input)}`)
}

describe('DraftsView', () => {
  it('shows one card per venue and night, with each post, its acts and its evidence', async () => {
    const { wrapper } = await mountView()

    const nights = wrapper.findAll('[data-testid="night"]')
    expect(nights).toHaveLength(2)
    expect(nights[0]!.text()).toContain('XTRUDE')
    expect(nights[0]!.text()).toContain('2 posts')
    expect(nights[0]!.text()).toContain('space.trax')
    expect(nights[0]!.text()).toContain('next Saturday, October 17th')
    expect(nights[1]!.text()).toContain('none named')
    expect(wrapper.find('[data-testid="pending-count"]').text()).toBe('3 pending')
  })

  it('opens the New event form with the first draft of the night', async () => {
    const { wrapper, router } = await mountView()

    await wrapper
      .find('button[aria-label="Create the event for RSO.Berlin on 2026-10-17"]')
      .trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('new-event')
    expect(router.currentRoute.value.query.draft).toBe('1')
  })

  it('rejects a night and drops its card', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('button[aria-label="Reject Prisma on 2026-10-18"]').trigger('click')
    await flushPromises()

    expect(methods()).toContain('POST /api/admin/instagram-drafts/3/reject')
    expect(wrapper.findAll('[data-testid="night"]')).toHaveLength(1)
    expect(wrapper.find('[role="status"]').text()).toContain('Rejected: Prisma on 2026-10-18')
  })

  it('switches a venue off only on the second press', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('button[aria-label="Switch RSO.Berlin off"]').trigger('click')
    expect(methods()).not.toContain('DELETE /api/admin/instagram/venues/rso-berlin')

    await wrapper
      .find('button[aria-label="Confirm: switch RSO.Berlin off and delete its drafts"]')
      .trigger('click')
    await flushPromises()

    expect(methods()).toContain('DELETE /api/admin/instagram/venues/rso-berlin')
    expect(wrapper.find('[role="status"]').text()).toContain('2 draft(s) deleted')
  })
})
