import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

import { ApiError } from '@/api/client'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  fetchFollowNotify: vi.fn<(kind: string, slug: string) => Promise<string | null>>(),
  follow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  unfollow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  fetchFollows: vi.fn<() => Promise<unknown[]>>(),
  fetchFollowing: vi.fn<(page?: number, from?: string) => Promise<unknown>>(),
  fetchFollowingToday: vi.fn<() => Promise<unknown[]>>(),
  fetchSearches: vi.fn<() => Promise<unknown[]>>(),
}))
const getMock = vi.hoisted(() => vi.fn<(path: string, options?: unknown) => Promise<unknown>>())

vi.mock('@/api/account', () => account)
vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: FollowButton } = await import('@/components/FollowButton.vue')
const { default: FollowingView } = await import('@/views/account/FollowingView.vue')
const { setSession } = await import('@/composables/useSession')

const Page = defineComponent({ render: () => h('p') })
// The cards have their own tests; here only the title matters.
const Card = defineComponent({
  props: { event: { type: Object, required: true } },
  render() {
    return h('p', (this.event as { title: string }).title)
  },
})
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.follow.mockResolvedValue()
  account.unfollow.mockResolvedValue()
  account.fetchFollowingToday.mockResolvedValue([])
  account.fetchSearches.mockResolvedValue([])
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(component: object, path: string, props: Record<string, unknown> = {}) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(component, {
    props,
    global: { plugins: [router], stubs: { EventCard: Card, EventRow: Card } },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

describe('FollowButton', () => {
  it('signed out, links to sign-in and back to this page', async () => {
    setSession(null)
    const view = await mountAt(FollowButton, '/en/venues/sisyphos', {
      kind: 'venue',
      slug: 'sisyphos',
    })

    expect(view.find('a').attributes('href')).toBe('/en/sign-in?next=/en/venues/sisyphos')
    expect(account.fetchFollowNotify).not.toHaveBeenCalled()
  })

  it('signed in, follows on a press and then says Following, with the unfollow for screen readers', async () => {
    setSession(me as never)
    account.fetchFollowNotify.mockResolvedValue(null)
    const view = await mountAt(FollowButton, '/en/venues/sisyphos', {
      kind: 'venue',
      slug: 'sisyphos',
    })

    const button = view.find('button')
    expect(button.text()).toBe('Follow')
    await button.trigger('click')
    await flushPromises()

    expect(account.prepareCsrf).toHaveBeenCalled()
    expect(account.follow).toHaveBeenCalledWith('venue', 'sisyphos')
    expect(view.find('button').text()).toBe('Following (press to unfollow)')
    expect(view.emitted('change')).toEqual([[true]])
  })

  it('asks whether the account follows already, and links to Following after a follow', async () => {
    setSession(me as never)
    account.fetchFollowNotify.mockResolvedValue('off')
    const view = await mountAt(FollowButton, '/en/artists/ada', { kind: 'artist', slug: 'ada' })

    expect(account.fetchFollowNotify).toHaveBeenCalledWith('artist', 'ada')
    expect(view.find('button').text()).toBe('Following (press to unfollow)')

    await view.find('button').trigger('click')
    await flushPromises()
    expect(view.find('a[href="/en/following"]').exists()).toBe(false)
    await view.find('button').trigger('click')
    await flushPromises()
    expect(view.find('a[href="/en/following"]').text()).toBe('Shown on your Following page')
  })

  it('goes back and says why when the BFF refuses', async () => {
    setSession(me as never)
    account.fetchFollowNotify.mockResolvedValue(null)
    account.follow.mockRejectedValue(new ApiError(409, 'too many'))
    const view = await mountAt(FollowButton, '/en/artists/ada', { kind: 'artist', slug: 'ada' })

    await view.find('button').trigger('click')
    await flushPromises()

    expect(view.find('button').text()).toBe('Follow')
    expect(view.find('[role=alert]').text()).toContain('500')
  })
})

describe('FollowingView', () => {
  it('signed out, sends the visitor to sign-in and back', async () => {
    setSession(null)
    await mountAt(FollowingView, '/en/following')

    expect(router.currentRoute.value.fullPath).toBe('/en/sign-in?next=/en/following')
  })

  it('lists the upcoming events, with what the account follows on its own tab', async () => {
    setSession(me as never)
    account.fetchFollowing.mockResolvedValue({
      content: [
        {
          slug: 'ada-live',
          title: 'Ada live',
          eventDate: '2026-11-01',
          venue: { name: 'Bar', slug: 'bar' },
        },
      ],
      totalPages: 1,
    })
    account.fetchFollows.mockResolvedValue([
      { kind: 'artist', slug: 'ada', name: 'Ada', notify: 'off' },
    ])
    const view = await mountAt(FollowingView, '/en/following')

    expect(view.text()).toContain('Ada live')
    expect(view.find('a[href="/en/artists/ada"]').exists()).toBe(false)
    expect(getMock).not.toHaveBeenCalled()
    // Nothing today: no Today heading, and Upcoming starts tomorrow.
    expect(view.findAll('h2').map((h) => h.text())).not.toContain('Today')
    expect(account.fetchFollowing).toHaveBeenCalledWith(
      0,
      expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
    )
  })

  it("shows today's events above Upcoming, capped as on the home page", async () => {
    setSession(me as never)
    const today = new Date().toISOString().slice(0, 10)
    account.fetchFollowingToday.mockResolvedValue(
      Array.from({ length: 8 }, (_, i) => ({
        slug: `tonight-${i}`,
        title: `Tonight ${i}`,
        eventDate: today,
        venue: { name: 'Club', slug: 'club' },
      })),
    )
    account.fetchFollowing.mockResolvedValue({ content: [], totalPages: 0 })
    account.fetchFollows.mockResolvedValue([
      { kind: 'venue', slug: 'club', name: 'Club', notify: 'off' },
    ])
    const view = await mountAt(FollowingView, '/en/following')

    const headings = view.findAll('h2').map((h) => h.text())
    expect(headings.indexOf('Today')).toBeLessThan(headings.indexOf('Upcoming events'))
    expect(view.text()).toContain('Tonight 5')
    expect(view.text()).not.toContain('Tonight 6')
    await view
      .findAll('button')
      .find((b) => b.text().includes('Show all'))!
      .trigger('click')
    expect(view.text()).toContain('Tonight 7')
  })

  it('suggests the busiest venues when nothing is followed yet', async () => {
    setSession(me as never)
    account.fetchFollowing.mockResolvedValue({ content: [], totalPages: 0 })
    account.fetchFollows.mockResolvedValue([])
    getMock.mockResolvedValue({ content: [{ slug: 'sisyphos', name: 'Sisyphos' }] })
    const view = await mountAt(FollowingView, '/en/following')

    expect(getMock).toHaveBeenCalledWith('/api/venues', {
      params: { query: { sort: ['upcomingEvents,desc'], size: 6 } },
    })
    expect(view.find('a[href="/en/venues/sisyphos"]').text()).toBe('Sisyphos')
  })
})
