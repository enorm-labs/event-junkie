import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined
let router: Router

async function mountAt(path: string, props: Record<string, unknown> = {}) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(ClearAllFilters, { props, global: { plugins: [router] } })
  await flushPromises()
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('ClearAllFilters', () => {
  it.each([
    '/venues',
    '/venues?sort=upcomingEvents,desc&view=map&radius=5&page=2',
    '/map?radius=5',
  ])('is absent while nothing narrows the list: %s', async (path) => {
    await mountAt(path)
    expect(wrapper!.find('button').exists()).toBe(false)
  })

  it.each(['/promoters?q=berlin', '/venues?character=smoke-free'])(
    'shows "Clear all" once a filter or a search is set: %s',
    async (path) => {
      await mountAt(path)
      expect(wrapper!.get('button').text()).toBe('Clear all')
    },
  )

  it('clears every filter and keeps the order and the view', async () => {
    await mountAt(
      '/venues?q=club&character=smoke-free&district=mitte&sort=upcomingEvents,desc&view=map&page=3',
    )

    await wrapper!.get('button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ sort: 'upcomingEvents,desc', view: 'map' })
    expect(wrapper!.find('button').exists()).toBe(false)
  })

  it('reads "Clear all filters" in an empty state', async () => {
    await mountAt('/events?q=nothing', { emptyState: true })
    expect(wrapper!.get('button').text()).toBe('Clear all filters')
  })
})
