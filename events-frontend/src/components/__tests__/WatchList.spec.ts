import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  unwatchAct: vi.fn<(mbid: string) => Promise<void>>(),
  fetchWatches: vi.fn<() => Promise<unknown[]>>(),
  searchMusicBrainz: vi.fn<(query: string) => Promise<unknown[]>>(),
  watchAct: vi.fn<(mbid: string) => Promise<unknown>>(),
}))
vi.mock('@/api/account', () => account)

const { default: WatchList } = await import('@/components/WatchList.vue')

const Page = defineComponent({ render: () => h('p') })
const MODERAT = { mbid: 'aaaaaaaa-0000-4000-8000-000000000001', name: 'Moderat' }

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.unwatchAct.mockResolvedValue()
  account.fetchWatches.mockResolvedValue([])
})

async function mountList(watches: { mbid: string; name: string }[]) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push('/en/following?tab=follows')
  const view = mount(WatchList, { props: { watches }, global: { plugins: [router] } })
  await flushPromises()
  return view
}

describe('WatchList', () => {
  it('lists each watched act as waiting for a Berlin date, and removes one', async () => {
    const view = await mountList([MODERAT])

    expect(view.find('h2').text()).toBe('Waiting for a Berlin date (1)')
    expect(view.find('li').text()).toContain('No Berlin date yet')
    const remove = view.find('li button')
    expect(remove.attributes('aria-label')).toBe('Remove Moderat')
    await remove.trigger('click')
    await flushPromises()

    expect(account.unwatchAct).toHaveBeenCalledWith(MODERAT.mbid)
    expect(view.emitted('change')).toHaveLength(1)
  })

  it('puts no heading over an empty list, only the sentence and the button', async () => {
    const view = await mountList([])

    expect(view.find('h2').exists()).toBe(false)
    expect(view.text()).toContain('Waiting for an act that has not played Berlin yet?')
    expect(view.find('button').text()).toContain('Add an act')
  })

  it('opens the picker on Add an act, and asks MusicBrainz nothing before a name is typed', async () => {
    const view = await mountList([])

    await view.find('button').trigger('click')
    await flushPromises()

    expect(view.find('input[type="search"]').exists()).toBe(true)
    expect(account.searchMusicBrainz).not.toHaveBeenCalled()
  })

  it('says so when the removal fails, and keeps the row', async () => {
    account.unwatchAct.mockRejectedValue(new Error('offline'))
    const view = await mountList([MODERAT])

    await view.find('li button').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('That did not work. Please try again.')
    expect(view.emitted('change')).toBeUndefined()
  })
})
