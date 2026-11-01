import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import { todayIso } from '@/lib/format'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  isFollowing: vi.fn<(kind: string, slug: string) => Promise<boolean>>(),
  follow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  unfollow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  setNotify: vi.fn<(kind: string, slug: string, notify: string) => Promise<void>>(),
  fetchFollows: vi.fn<() => Promise<unknown[]>>(),
  fetchFollowing: vi.fn<(...args: unknown[]) => Promise<unknown>>(),
  fetchFollowingToday: vi.fn<() => Promise<unknown[]>>(),
  fetchSearches: vi.fn<() => Promise<unknown[]>>(),
  fetchPushKey: vi.fn<() => Promise<string | null>>(),
}))
const getMock = vi.hoisted(() => vi.fn<(path: string, options?: unknown) => Promise<unknown>>())

vi.mock('@/api/account', () => account)
vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: FollowingView } = await import('@/views/account/FollowingView.vue')
const { setSession } = await import('@/composables/useSession')

const Page = defineComponent({ render: () => h('p') })
const Card = defineComponent({
  props: { event: { type: Object, required: true } },
  render() {
    return h('p', (this.event as { title: string }).title)
  },
})
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
const adaLive = {
  slug: 'ada-live',
  title: 'Ada live',
  eventDate: '2026-11-01',
  venue: { name: 'Bar', slug: 'bar' },
}
const ada = { kind: 'artist', slug: 'ada', name: 'Ada', notify: 'off' }
const bar = { kind: 'venue', slug: 'bar', name: 'Bar', notify: 'off' }

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.unfollow.mockResolvedValue()
  account.fetchFollowingToday.mockResolvedValue([])
  account.fetchSearches.mockResolvedValue([])
  // A key, so the site sends push and the bells show; jsdom then reads as a browser without it.
  account.fetchPushKey.mockResolvedValue('key')
  account.fetchFollowing.mockResolvedValue({ content: [adaLive], totalPages: 1 })
  account.fetchFollows.mockResolvedValue([ada, bar])
  setSession(me as never)
  // The view tags the history entry; a memory router never replaces jsdom's, so each test starts as a fresh visit.
  window.history.replaceState(null, '')
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(FollowingView, {
    global: { plugins: [router], stubs: { EventCard: Card, EventRow: Card } },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

const tabs = (view: VueWrapper) => view.findAll('[role=tab]')
const selected = (view: VueWrapper) =>
  tabs(view)
    .filter((tab) => tab.attributes('aria-selected') === 'true')
    .map((tab) => tab.text())
const followsPanel = (view: VueWrapper) => view.find('#following-panel-follows')

describe('FollowingView tabs (#4080)', () => {
  it('opens on Events, with what the account follows counted on the other tab', async () => {
    const view = await mountAt('/en/following')

    expect(tabs(view).map((tab) => tab.text())).toEqual(['Events', 'You follow (2)'])
    expect(selected(view)).toEqual(['Events'])
    expect(view.text()).toContain('Ada live')
    expect(followsPanel(view).exists()).toBe(false)
  })

  it('puts the tab in the URL and keeps the rest of the query', async () => {
    const view = await mountAt('/en/following?free=true')

    await tabs(view)[1]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ free: 'true', tab: 'follows' })
    expect(selected(view)).toEqual(['You follow (2)'])
    expect(view.find('#following-panel-events').isVisible()).toBe(false)
    expect(
      followsPanel(view)
        .findAll('h2')
        .map((h) => h.text()),
    ).toEqual(['Artists (1)', 'Venues (1)'])

    await tabs(view)[0]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/en/following?free=true')
  })

  it('opens on the tab the URL names, and moves between tabs by arrow key', async () => {
    const view = await mountAt('/en/following?tab=follows')
    expect(selected(view)).toEqual(['You follow (2)'])
    expect(tabs(view).map((tab) => tab.attributes('tabindex'))).toEqual(['-1', '0'])

    await tabs(view)[1]!.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    expect(selected(view)).toEqual(['Events'])
    expect(document.activeElement?.id).toBe('following-tab-events')
  })

  it('keeps an unfollowed row until the visitor leaves the tab, then reloads without it', async () => {
    const view = await mountAt('/en/following?tab=follows')
    const adaRow = () => followsPanel(view).findAll('li')[0]!

    expect(adaRow().find('button[aria-label="Push for new dates of Ada"]').exists()).toBe(true)
    await adaRow()
      .findAll('button')
      .find((b) => b.text().startsWith('Following'))!
      .trigger('click')
    await flushPromises()

    expect(account.unfollow).toHaveBeenCalledWith('artist', 'ada')
    expect(adaRow().text()).toContain('Ada')
    expect(adaRow().find('button[aria-label="Push for new dates of Ada"]').exists()).toBe(false)
    expect(tabs(view)[1]!.text()).toBe('You follow (1)')
    expect(account.fetchFollows).toHaveBeenCalledTimes(1)

    account.fetchFollows.mockResolvedValue([bar])
    await tabs(view)[0]!.trigger('click')
    await flushPromises()
    expect(account.fetchFollows).toHaveBeenCalledTimes(2)

    await tabs(view)[1]!.trigger('click')
    await flushPromises()
    expect(
      followsPanel(view)
        .findAll('li a')
        .map((a) => a.text()),
    ).toEqual(['Bar'])
  })

  it('reloads nothing on leaving the tab when nothing changed', async () => {
    const view = await mountAt('/en/following?tab=follows')
    await tabs(view)[0]!.trigger('click')
    await flushPromises()
    expect(account.fetchFollows).toHaveBeenCalledTimes(1)
  })

  it('runs saved searches from chips beside the filters and manages them on the follows tab (#4099)', async () => {
    account.fetchSearches.mockResolvedValue([
      { id: 'a', name: 'Free techno', query: 'free=true&genre=techno' },
    ])
    const view = await mountAt('/en/following')

    expect(tabs(view)[1]!.text()).toBe('You follow (3)')
    const chips = view.find('#following-panel-events ul[aria-label="Your saved searches"]')
    expect(chips.find('a').attributes('href')).toBe('/en/events?free=true&genre=techno')
    expect(view.find('#following-panel-events').text()).not.toContain('Saved searches (1)')

    await tabs(view)[1]!.trigger('click')
    await flushPromises()
    expect(
      followsPanel(view)
        .findAll('h2')
        .map((h) => h.text()),
    ).toEqual(['Artists (1)', 'Venues (1)', 'Saved searches (1)'])
  })

  it('says so on the tab when the account follows nothing', async () => {
    account.fetchFollows.mockResolvedValue([])
    getMock.mockResolvedValue({ content: [] })
    const view = await mountAt('/en/following?tab=follows')

    expect(tabs(view)[1]!.text()).toBe('You follow (0)')
    expect(followsPanel(view).text()).toContain(
      'You do not follow an artist, a venue or a promoter yet.',
    )
  })
})

describe('FollowingView on Back from an event', () => {
  const night = (n: number) => ({ slug: `night-${n}`, title: `Night ${n}`, eventDate: todayIso() })
  const later = (n: number) => ({
    slug: `later-${n}`,
    title: `Later ${n}`,
    eventDate: '2099-01-01',
  })

  async function expandBoth(view: VueWrapper) {
    await view
      .findAll('button')
      .find((b) => b.text().startsWith('Show all 8'))!
      .trigger('click')
    await view
      .findAll('button')
      .find((b) => b.text() === 'Show more')!
      .trigger('click')
    await flushPromises()
  }

  beforeEach(() => {
    account.fetchFollowingToday.mockResolvedValue(Array.from({ length: 8 }, (_, n) => night(n)))
    account.fetchFollowing.mockImplementation((page) =>
      Promise.resolve({ content: [later(page as number)], totalPages: 3, totalElements: 3 }),
    )
  })

  it('paints Show all today and the pages Show more added before it refetches, then refetches them all', async () => {
    const first = await mountAt('/en/following')
    await expandBoth(first)
    first.unmount()
    account.fetchFollowing.mockClear()

    wrapper = mount(FollowingView, {
      global: { plugins: [router], stubs: { EventCard: Card, EventRow: Card } },
    })
    expect(wrapper.text()).toContain('Night 7')
    expect(wrapper.text()).toContain('Later 1')
    await flushPromises()
    expect(account.fetchFollowing.mock.calls.map(([page]) => page)).toEqual([0, 1])
    expect(wrapper.text()).toContain('Night 7')
    expect(wrapper.text()).toContain('Later 1')
  })

  it('starts collapsed on a fresh visit', async () => {
    const first = await mountAt('/en/following')
    await expandBoth(first)
    first.unmount()
    window.history.replaceState(null, '')

    const view = await mountAt('/en/following')
    expect(view.text()).not.toContain('Night 7')
    expect(view.text()).not.toContain('Later 1')
  })
})
