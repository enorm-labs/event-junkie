import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchWatches: vi.fn<() => Promise<{ mbid: string; name: string }[]>>(),
  searchMusicBrainz: vi.fn<(query: string) => Promise<unknown[]>>(),
  watchAct: vi.fn<(mbid: string) => Promise<{ followed: string | null }>>(),
}))
vi.mock('@/api/account', () => account)

const { default: WatchPicker } = await import('@/components/WatchPicker.vue')
const { ApiError } = await import('@/api/client')

const Page = defineComponent({ render: () => h('p') })
const MODERAT = {
  mbid: 'aaaaaaaa-0000-4000-8000-000000000001',
  name: 'Moderat',
  type: 'Group',
  country: 'DE',
  disambiguation: 'Berlin electronic trio',
}
const NAMESAKE = {
  mbid: 'aaaaaaaa-0000-4000-8000-000000000002',
  name: 'Moderat',
  type: 'Group',
  country: 'SE',
}
const KNOWN = {
  mbid: 'aaaaaaaa-0000-4000-8000-000000000003',
  name: 'Moderatto',
  followSlug: 'moderatto',
}

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  account.prepareCsrf.mockResolvedValue()
  account.fetchWatches.mockResolvedValue([])
  account.searchMusicBrainz.mockResolvedValue([MODERAT, NAMESAKE, KNOWN])
  account.watchAct.mockResolvedValue({ followed: null })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
})

async function mountPicker(props: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push('/en/search')
  wrapper = mount(WatchPicker, { props, global: { plugins: [router] } })
  await vi.runAllTimersAsync()
  await flushPromises()
  return wrapper
}

const rows = (view: VueWrapper) => view.findAll('li')
const input = (view: VueWrapper) => view.find('input')

describe('WatchPicker', () => {
  it('searches the initial term at once, and shows each candidate with what tells namesakes apart', async () => {
    const view = await mountPicker({ initialQuery: 'Moderat' })

    expect(account.searchMusicBrainz).toHaveBeenCalledExactlyOnceWith('Moderat')
    expect(rows(view)).toHaveLength(3)
    expect(rows(view)[0]!.text()).toContain('Berlin electronic trio · Group · DE')
    expect(rows(view)[1]!.text()).toContain('Group · SE')
    expect(view.text()).toContain(
      'Your search goes to MusicBrainz from our server, without your IP address.',
    )
  })

  it('waits until typing stops, and never asks for fewer than three characters', async () => {
    const view = await mountPicker()
    expect(account.searchMusicBrainz).not.toHaveBeenCalled()
    expect(view.text()).toContain('Type at least three characters.')

    await input(view).setValue('Mo')
    await vi.advanceTimersByTimeAsync(1000)
    expect(account.searchMusicBrainz).not.toHaveBeenCalled()

    await input(view).setValue('Mod')
    await vi.advanceTimersByTimeAsync(200)
    await input(view).setValue('Moderat')
    await vi.advanceTimersByTimeAsync(399)
    expect(account.searchMusicBrainz).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(account.searchMusicBrainz).toHaveBeenCalledExactlyOnceWith('Moderat')
  })

  it('keeps a slow answer to an older name from replacing the newer one', async () => {
    let answerOld: (value: unknown[]) => void = () => {}
    account.searchMusicBrainz.mockImplementationOnce(
      () => new Promise((resolve) => (answerOld = resolve)),
    )
    account.searchMusicBrainz.mockResolvedValueOnce([NAMESAKE])
    const view = await mountPicker({ initialQuery: 'Moder' })

    await input(view).setValue('Moderat Sweden')
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()
    answerOld([MODERAT, KNOWN])
    await flushPromises()

    expect(rows(view)).toHaveLength(1)
    expect(rows(view)[0]!.text()).toContain('SE')
  })

  it('says so when MusicBrainz knows nothing, and when it does not answer', async () => {
    account.searchMusicBrainz.mockResolvedValueOnce([])
    const view = await mountPicker({ initialQuery: 'Xqzv' })
    expect(view.text()).toContain('MusicBrainz does not know “Xqzv” either.')

    account.searchMusicBrainz.mockRejectedValueOnce(new ApiError(503, 'unavailable'))
    await input(view).setValue('Moderat')
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()
    expect(view.text()).toContain('MusicBrainz is not answering. Try again in a minute.')
  })

  it('watches an act not on the site, and marks it', async () => {
    const view = await mountPicker({ initialQuery: 'Moderat' })

    const button = rows(view)[0]!.find('button')
    expect(button.attributes('aria-label')).toBe('Notify me when Moderat plays Berlin')
    await button.trigger('click')
    await flushPromises()

    expect(account.watchAct).toHaveBeenCalledWith(MODERAT.mbid)
    expect(rows(view)[0]!.text()).toContain('We will tell you')
    expect(rows(view)[0]!.find('button').exists()).toBe(false)
    expect(view.emitted('change')).toHaveLength(1)
    expect(view.find('a[href="/en/following?tab=follows"]').exists()).toBe(true)
  })

  it('offers a follow, and a link to the artist, for an act the site carries already', async () => {
    account.watchAct.mockResolvedValue({ followed: 'moderatto' })
    const view = await mountPicker({ initialQuery: 'Moderat' })

    expect(rows(view)[2]!.find('a[href="/en/artists/moderatto"]').exists()).toBe(true)
    const button = rows(view)[2]!.find('button')
    expect(button.text()).toContain('Follow')
    await button.trigger('click')
    await flushPromises()

    expect(rows(view)[2]!.text()).toContain('Followed: it is on the site already')
  })

  it('marks what the account watches already, without a press', async () => {
    account.fetchWatches.mockResolvedValue([{ mbid: MODERAT.mbid, name: 'Moderat' }])
    const view = await mountPicker({ initialQuery: 'Moderat' })

    expect(rows(view)[0]!.text()).toContain('We will tell you')
    expect(rows(view)[1]!.find('button').exists()).toBe(true)
  })

  it('says the cap when the account follows 500 things', async () => {
    account.watchAct.mockRejectedValue(new ApiError(409, 'too many'))
    const view = await mountPicker({ initialQuery: 'Moderat' })

    await rows(view)[0]!.find('button').trigger('click')
    await flushPromises()

    expect(view.find('[role="alert"]').text()).toBe(
      'You follow 500 things already. Unfollow one first.',
    )
    expect(rows(view)[0]!.find('button').exists()).toBe(true)
  })
})
