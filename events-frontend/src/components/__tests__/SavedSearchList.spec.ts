import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSearches: vi.fn<() => Promise<unknown[]>>(),
  renameSearch: vi.fn<(id: string, name: string) => Promise<void>>(),
  deleteSearch: vi.fn<(id: string) => Promise<void>>(),
}))
const events = vi.hoisted(() => ({ searchEvents: vi.fn<(params: unknown) => Promise<unknown>>() }))
vi.mock('@/api/account', () => account)
vi.mock('@/composables/useEvents', () => events)

const { default: SavedSearchList } = await import('@/components/SavedSearchList.vue')
const { useSavedSearches } = await import('@/composables/useSavedSearches')

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.renameSearch.mockResolvedValue()
  account.deleteSearch.mockResolvedValue()
  account.fetchSearches.mockResolvedValue([
    { id: 'a', name: 'Free techno', query: 'free=true&genre=techno' },
  ])
  events.searchEvents.mockResolvedValue({ totalElements: 12 })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountList() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push('/en/following')
  const saved = useSavedSearches()
  await saved.load()
  wrapper = mount(SavedSearchList, { props: { saved }, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('SavedSearchList', () => {
  it('shows each search with its count and a link to the list with its filters', async () => {
    const view = await mountList()

    expect(view.find('h2').text()).toBe('Saved searches (1)')
    expect(view.find('a').attributes('href')).toBe('/en/events?free=true&genre=techno')
    expect(view.text()).toContain('12 upcoming')
    expect(events.searchEvents).toHaveBeenCalledWith({ free: true, genre: ['techno'], size: 1 })
  })

  it('renames a search in place', async () => {
    const view = await mountList()
    await view
      .findAll('button')
      .find((b) => b.text() === 'Rename')!
      .trigger('click')
    await view.find('input').setValue('Weekend techno')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(account.renameSearch).toHaveBeenCalledWith('a', 'Weekend techno')
    expect(view.find('a').text()).toBe('Weekend techno')
  })

  it('deletes a search, and with the last one the section goes', async () => {
    const view = await mountList()
    await view
      .findAll('button')
      .find((b) => b.text() === 'Delete')!
      .trigger('click')
    await flushPromises()

    expect(account.deleteSearch).toHaveBeenCalledWith('a')
    expect(view.find('section').exists()).toBe(false)
  })

  it('keeps the row and says so when the delete fails', async () => {
    account.deleteSearch.mockRejectedValue(new Error('down'))
    const view = await mountList()
    await view
      .findAll('button')
      .find((b) => b.text() === 'Delete')!
      .trigger('click')
    await flushPromises()

    expect(view.find('[role=alert]').text()).toBe('That did not work. Please try again.')
    expect(view.find('a').text()).toBe('Free techno')
  })
})
