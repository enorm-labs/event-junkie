import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import ListFilterBar from '@/components/ListFilterBar.vue'

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(path: string, props: { moreCount?: number }, withMore: boolean) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: defineComponent({ render: () => h('p') }) }],
  })
  await router.push(path)
  wrapper = mount(ListFilterBar, {
    props: { placeholder: 'Search', ...props },
    slots: withMore ? { more: () => h('select', { 'aria-label': 'District' }) } : {},
    global: { plugins: [router] },
  })
  await flushPromises()
}

/** Whether `v-show` hides the section; jsdom has no layout to ask. */
function moreShown(): boolean {
  return !wrapper!.get('#more-filters').attributes('style')?.includes('display: none')
}

/** The bar's labelled buttons; the search field's own ✕ is an icon. */
function buttons(): string[] {
  return wrapper!
    .findAll('button')
    .map((button) => button.text())
    .filter(Boolean)
}

describe('ListFilterBar', () => {
  it('puts the selects behind a closed "More filters", beside "Clear all"', async () => {
    await mountAt('/venues?q=club', {}, true)

    expect(buttons()).toEqual(['More filters', 'Clear all'])
    expect(moreShown()).toBe(false)

    await wrapper!.get('[aria-controls="more-filters"]').trigger('click')
    expect(moreShown()).toBe(true)
  })

  it('opens with the count when the URL already sets a filter behind it', async () => {
    await mountAt('/venues?district=mitte', { moreCount: 1 }, true)

    expect(buttons()[0]).toBe('More filters (1)')
    expect(moreShown()).toBe(true)
  })

  it('shows no toggle without selects, as on the promoters list', async () => {
    await mountAt('/promoters', {}, false)

    expect(wrapper!.find('[aria-controls="more-filters"]').exists()).toBe(false)
    expect(wrapper!.find('#more-filters').exists()).toBe(false)
    expect(wrapper!.find('form[role="search"] input').exists()).toBe(true)
  })
})
