import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  isFollowing: vi.fn<(kind: string, slug: string) => Promise<boolean>>(),
  follow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  unfollow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  setNotify: vi.fn<(kind: string, slug: string, notify: string) => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: FollowedList } = await import('@/components/FollowedList.vue')
const { setSession } = await import('@/composables/useSession')

type Kind = 'artist' | 'venue' | 'promoter'
const followed = (kind: Kind, name: string) => ({
  kind,
  slug: name.toLowerCase().split(' ').join('-'),
  name,
  notify: 'off' as const,
  following: true,
})
/** `n` artists named Act 01, Act 02, …, so their order by name is known. */
const artists = (n: number) =>
  Array.from({ length: n }, (_, i) => followed('artist', `Act ${String(i + 1).padStart(2, '0')}`))

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.unfollow.mockResolvedValue()
  account.follow.mockResolvedValue()
  setSession({ email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' } as never)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountList(follows: ReturnType<typeof followed>[], bells = true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push('/en/following?tab=follows')
  wrapper = mount(FollowedList, { props: { follows, bells }, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const headings = (view: VueWrapper) => view.findAll('h2').map((h) => h.text())
const names = (view: VueWrapper) => view.findAll('li a').map((a) => a.text())

describe('FollowedList', () => {
  it('groups by kind in a fixed order, sorts each by name, and leaves out a kind with none', async () => {
    const view = await mountList([
      followed('venue', 'Sisyphos'),
      followed('artist', 'zara'),
      followed('venue', 'about blank'),
      followed('artist', 'Ada'),
    ])

    expect(headings(view)).toEqual(['Artists (2)', 'Venues (2)'])
    expect(names(view)).toEqual(['Ada', 'zara', 'about blank', 'Sisyphos'])
    expect(view.find('a[href="/en/venues/sisyphos"]').exists()).toBe(true)
    expect(view.find('a[href="/en/artists/ada"]').exists()).toBe(true)
  })

  it('shows no search box at ten follows', async () => {
    const view = await mountList(artists(10))
    expect(view.find('input[type=search]').exists()).toBe(false)
  })

  it('shows the search box at eleven, and narrows the names by it', async () => {
    const view = await mountList([...artists(10), followed('promoter', 'Mock Promoter')])

    const search = view.find('input[type=search]')
    expect(search.attributes('aria-label')).toBe('Search what you follow')
    await search.setValue('  PROMO ')

    expect(names(view)).toEqual(['Mock Promoter'])
    expect(headings(view)).toEqual(['Promoters (1)'])

    await search.setValue('nothing like it')
    expect(names(view)).toEqual([])
    expect(view.find('[role=status]').text()).toBe('Nothing you follow matches “nothing like it”.')
  })

  it('keeps an unfollowed row with Follow in its place, and tells the page', async () => {
    const follows = [followed('artist', 'Ada')]
    const view = await mountList(follows)
    expect(view.find('button[aria-label="Push for new dates of Ada"]').exists()).toBe(true)

    await view
      .findAll('button')
      .find((b) => b.text().startsWith('Following'))!
      .trigger('click')
    await flushPromises()

    expect(account.unfollow).toHaveBeenCalledWith('artist', 'ada')
    expect(view.emitted('change')).toEqual([[follows[0], false]])
    expect(names(view)).toEqual(['Ada'])
    expect(view.findAll('li button').slice(-1)[0]!.text()).toBe('Follow')
  })

  it('says the account follows nothing yet', async () => {
    const view = await mountList([])
    expect(view.text()).toContain('You do not follow an artist, a venue or a promoter yet.')
    expect(view.find('input[type=search]').exists()).toBe(false)
  })
})
