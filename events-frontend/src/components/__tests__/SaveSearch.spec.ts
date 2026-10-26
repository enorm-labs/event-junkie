import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

import { ApiError } from '@/api/client'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  saveSearch: vi.fn<(name: string, query: string) => Promise<unknown>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
}))
vi.mock('@/api/account', () => account)

const { default: SaveSearch } = await import('@/components/SaveSearch.vue')
const { setSession } = await import('@/composables/useSession')

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.saveSearch.mockResolvedValue({ id: '1', name: 'x', query: 'x' })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

interface Props {
  filters: Record<string, string | boolean | string[] | undefined>
  range: { from?: string; to?: string }
  labels: string[]
}

async function mountWith(props: Props) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push('/en/events')
  wrapper = mount(SaveSearch, { props, global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('SaveSearch', () => {
  it('is absent when no filter is set', async () => {
    setSession(me as never)
    const view = await mountWith({ filters: { free: undefined }, range: {}, labels: [] })
    expect(view.text()).toBe('')
  })

  it('signed out, leads to sign-in and back to the list', async () => {
    setSession(null)
    const view = await mountWith({ filters: { genre: ['techno'] }, range: {}, labels: ['Techno'] })
    expect(view.find('a').attributes('href')).toBe('/en/sign-in?next=/en/events')
  })

  it('saves one genre alone with a single press, named after it', async () => {
    setSession(me as never)
    const view = await mountWith({ filters: { genre: ['techno'] }, range: {}, labels: ['Techno'] })
    expect(view.find('[data-testid="save-search"]').text()).toBe('Follow Techno')

    await view.find('[data-testid="save-search"]').trigger('click')
    await flushPromises()

    expect(account.saveSearch).toHaveBeenCalledWith('Techno', 'genre=techno')
    expect(view.find('[role="status"]').text()).toContain('Saved.')
  })

  it('suggests a name from the filters, which the visitor can change', async () => {
    setSession(me as never)
    const view = await mountWith({
      filters: { genre: ['techno'], free: true },
      range: {},
      labels: ['Techno', 'Free'],
    })
    await view.find('[data-testid="save-search"]').trigger('click')
    await flushPromises()

    const input = document.querySelector<HTMLInputElement>('#saved-search-name')!
    expect(input.value).toBe('Techno, Free')
    input.value = 'Free techno'
    input.dispatchEvent(new Event('input'))
    document.querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(account.saveSearch).toHaveBeenCalledWith('Free techno', 'genre=techno&free=true')
  })

  it('says when the account holds 20 searches already', async () => {
    setSession(me as never)
    account.saveSearch.mockRejectedValue(new ApiError(409, 'full'))
    const view = await mountWith({ filters: { district: ['mitte'] }, range: {}, labels: ['Mitte'] })
    await view.find('[data-testid="save-search"]').trigger('click')
    await flushPromises()

    expect(view.find('[role="status"]').text()).toContain('20 saved searches')
  })
})
