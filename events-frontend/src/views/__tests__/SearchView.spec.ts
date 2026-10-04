import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<() => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: SearchView } = await import('@/views/SearchView.vue')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('SearchView', () => {
  it("styles 'Show all' as a link, not as the section label (#2668)", async () => {
    getMock.mockResolvedValue({
      venues: { items: [], total: 0 },
      events: { items: [], total: 100, totalCapped: true },
      artists: { items: [], total: 0 },
      promoters: { items: [], total: 0 },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
    })
    await router.push('/en/search?q=techno')
    wrapper = mount(SearchView, { global: { plugins: [router] } })
    await flushPromises()

    const showAll = wrapper.find('a[href="/en/events?q=techno"]')
    expect(showAll.exists()).toBe(true)
    expect(showAll.classes()).toEqual(
      expect.arrayContaining(['text-primary', 'underline-offset-4', 'hover:underline']),
    )
    expect(showAll.classes()).not.toContain('text-muted-foreground')
  })
})
