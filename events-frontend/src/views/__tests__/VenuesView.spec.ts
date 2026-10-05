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

const { default: VenuesView } = await import('@/views/VenuesView.vue')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('VenuesView', () => {
  // Without a step the subtitle fell back to the browser's 16/24 (#2691).
  it('sets the subtitle on the body step', async () => {
    getMock.mockResolvedValue({ content: [], page: { totalElements: 0, totalPages: 0 } })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
    })
    await router.push('/en/venues')
    wrapper = mount(VenuesView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.get('header p').classes()).toContain('text-body')
  })
})
