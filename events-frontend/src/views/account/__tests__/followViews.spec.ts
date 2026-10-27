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
  fetchFollowing: vi.fn<(page?: number, query?: Record<string, unknown>) => Promise<unknown>>(),
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
const { addDays, tomorrowIso } = await import('@/lib/format')

const Page = defineComponent({ render: () => h('p') })
// The cards have their own tests; here only the title matters.
const Card = defineComponent({
  props: { event: { type: Object, required: true } },
  render() {
    return h('p', (this.event as { title: string }).title)
  },
})
// The bar has its own tests; here only whether it is there.
const Bar = defineComponent({
  props: { hideTodayPreset: Boolean, resultCount: { type: Number, default: undefined } },
  render() {
    return h('div', {
      'data-testid': 'filter-bar',
      'data-hide-today': String(this.hideTodayPreset),
    })
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
    global: { plugins: [router], stubs: { EventCard: Card, EventRow: Card, EventFilterBar: Bar } },
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
    expect(account.fetchFollowing).toHaveBeenCalledWith(0, {
      from: tomorrowIso(),
      sort: undefined,
    })
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

  describe('filters and sort (#4079)', () => {
    const adaLive = {
      slug: 'ada-live',
      title: 'Ada live',
      eventDate: '2026-11-01',
      venue: { name: 'Bar', slug: 'bar' },
    }

    beforeEach(() => {
      setSession(me as never)
      account.fetchFollows.mockResolvedValue([
        { kind: 'artist', slug: 'ada', name: 'Ada', notify: 'off' },
      ])
    })

    const filterButton = (view: VueWrapper) =>
      view.findAll('button').find((b) => b.text().startsWith('Filter'))!

    it('keeps the bar behind one Filter button, closed until pressed', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
      const view = await mountAt(FollowingView, '/en/following')

      const button = filterButton(view)
      expect(button.text()).toBe('Filter')
      expect(button.attributes('aria-expanded')).toBe('false')
      expect(view.find('[data-testid=filter-bar]').exists()).toBe(false)

      await button.trigger('click')
      expect(filterButton(view).attributes('aria-expanded')).toBe('true')
      expect(view.find('#following-filters [data-testid=filter-bar]').exists()).toBe(true)
    })

    /** Whether the sort control comes after the bar in the page, as on the events page. */
    const sortAfterBar = (view: VueWrapper) => {
      const bar = view.find('[data-testid=filter-bar]').element
      const sort = view.findAll('button').find((b) => b.text() === 'Newest added')!.element
      return Boolean(bar.compareDocumentPosition(sort) & Node.DOCUMENT_POSITION_FOLLOWING)
    }

    it('keeps the sort beside the closed Filter button, and moves it under the open bar with the count', async () => {
      account.fetchFollowing.mockResolvedValue({
        content: [adaLive],
        totalElements: 1,
        totalPages: 1,
      })
      const view = await mountAt(FollowingView, '/en/following')
      expect(view.findAll('button').some((b) => b.text() === 'Newest added')).toBe(true)
      expect(view.text()).not.toContain('1 event found')

      await filterButton(view).trigger('click')
      expect(sortAfterBar(view)).toBe(true)
      expect(view.findAll('button').filter((b) => b.text() === 'Newest added')).toHaveLength(1)
      expect(view.text()).toContain('1 event found')
      // Upcoming starts tomorrow, so the Today preset would always empty it.
      expect(view.find('[data-testid=filter-bar]').attributes('data-hide-today')).toBe('true')
    })

    it('sends the URL filters and range, opens the bar by itself, and counts them on the button', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
      const from = addDays(tomorrowIso(), 2)
      const to = addDays(tomorrowIso(), 4)
      const view = await mountAt(
        FollowingView,
        `/en/following?eventType=CONCERT&free=true&from=${from}&to=${to}`,
      )

      expect(account.fetchFollowing).toHaveBeenCalledWith(
        0,
        expect.objectContaining({ eventType: ['CONCERT'], free: true, from, to }),
      )
      expect(filterButton(view).text()).toBe('Filter (3)')
      expect(view.find('[data-testid=filter-bar]').exists()).toBe(true)
      // Today keeps its own section, unfiltered.
      expect(account.fetchFollowingToday).toHaveBeenCalledWith()
    })

    it('starts a range that begins today or earlier at tomorrow, so today never shows twice', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [], totalPages: 0 })
      const today = addDays(tomorrowIso(), -1)
      await mountAt(FollowingView, `/en/following?from=${today}&to=${today}`)

      expect(account.fetchFollowing).toHaveBeenCalledWith(
        0,
        expect.objectContaining({ from: tomorrowIso(), to: today }),
      )
    })

    it('reloads the list in the newest-added order, and keeps that order out of the URL as the default', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
      const view = await mountAt(FollowingView, '/en/following')

      await view
        .findAll('button')
        .find((b) => b.text() === 'Newest added')!
        .trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.query.sort).toBe('createdAt,desc')
      expect(account.fetchFollowing).toHaveBeenLastCalledWith(
        0,
        expect.objectContaining({ sort: ['createdAt,desc'] }),
      )
      expect(account.fetchFollows).toHaveBeenCalledTimes(1)

      await view
        .findAll('button')
        .find((b) => b.text() === 'Date')!
        .trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.query.sort).toBeUndefined()
    })

    it('says a filter emptied the list, not that nothing is followed, and clears it', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [], totalPages: 0 })
      const view = await mountAt(
        FollowingView,
        '/en/following?eventType=CONCERT&sort=createdAt,desc',
      )

      expect(view.text()).toContain('Nothing you follow matches these filters.')
      expect(view.text()).not.toContain('No upcoming events right now.')
      expect(view.text()).not.toContain('You do not follow anything yet.')

      account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
      await view
        .findAll('button')
        .find((b) => b.text() === 'Clear all filters')!
        .trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.query).toEqual({ sort: 'createdAt,desc' })
      expect(view.text()).toContain('Ada live')
    })

    it('keeps the filters in the URL across the tabs, and the bar only in the Events panel', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
      const view = await mountAt(FollowingView, '/en/following?eventType=CONCERT')
      expect(view.find('#following-panel-events [data-testid=filter-bar]').exists()).toBe(true)

      await view.find('#following-tab-follows').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual({ eventType: 'CONCERT', tab: 'follows' })
      expect(view.find('#following-panel-follows [data-testid=filter-bar]').exists()).toBe(false)
      expect(account.fetchFollowing).toHaveBeenCalledTimes(1)

      await view.find('#following-tab-events').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual({ eventType: 'CONCERT' })
    })

    it('without a filter, an empty list says nothing is upcoming and offers no bar', async () => {
      account.fetchFollowing.mockResolvedValue({ content: [], totalPages: 0 })
      const view = await mountAt(FollowingView, '/en/following')

      expect(view.text()).toContain('No upcoming events right now.')
      expect(view.findAll('button').some((b) => b.text().startsWith('Filter'))).toBe(false)
    })

    describe('on a phone', () => {
      beforeEach(() => {
        vi.stubGlobal('matchMedia', (query: string) => ({
          matches: true,
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }))
      })

      afterEach(() => vi.unstubAllGlobals())

      it("shows the bar without the page's Filter button, so the bar's own is the only one", async () => {
        account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
        const view = await mountAt(FollowingView, '/en/following')

        expect(view.findAll('button').some((b) => b.text().startsWith('Filter'))).toBe(false)
        expect(view.find('[data-testid=filter-bar]').exists()).toBe(true)
        expect(view.find('#following-filters').exists()).toBe(false)
      })

      it('puts the count and the sort under the bar, as on the events page', async () => {
        account.fetchFollowing.mockResolvedValue({
          content: [adaLive],
          totalElements: 1,
          totalPages: 1,
        })
        const view = await mountAt(FollowingView, '/en/following')

        expect(sortAfterBar(view)).toBe(true)
        expect(view.text()).toContain('1 event found')
      })

      it('keeps the bar when a filter emptied the list, and offers no bar on an empty list without one', async () => {
        account.fetchFollowing.mockResolvedValue({ content: [], totalPages: 0 })
        const filtered = await mountAt(FollowingView, '/en/following?eventType=CONCERT')
        expect(filtered.find('[data-testid=filter-bar]').exists()).toBe(true)
        expect(filtered.text()).toContain('Nothing you follow matches these filters.')
        filtered.unmount()

        const plain = await mountAt(FollowingView, '/en/following')
        expect(plain.find('[data-testid=filter-bar]').exists()).toBe(false)
        expect(plain.text()).toContain('No upcoming events right now.')
      })
    })
  })
})
