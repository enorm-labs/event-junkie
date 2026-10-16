import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import FilterSearchRow from '@/components/FilterSearchRow.vue'

let wrapper: VueWrapper<InstanceType<typeof FilterSearchRow>> | undefined
let router: Router

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(path: string, props: Record<string, unknown> = {}, slots = {}) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: defineComponent({ render: () => h('p') }) }],
  })
  await router.push(path)
  wrapper = mount(FilterSearchRow, {
    props: { placeholder: 'Search', ...props },
    slots,
    global: { plugins: [router] },
    attachTo: document.body,
  })
  await flushPromises()
}

const toggle = () => wrapper!.get('[aria-expanded]')

describe('FilterSearchRow', () => {
  it('shows the search and no toggle without a label', async () => {
    await mountAt('/promoters')

    expect(wrapper!.get('form[role="search"] input').attributes('placeholder')).toBe('Search')
    expect(wrapper!.find('[aria-expanded]').exists()).toBe(false)
  })

  it('names the panel it opens and emits a toggle on a click', async () => {
    await mountAt('/venues', { label: 'More filters', controls: 'more-filters', expanded: true })

    expect(toggle().text()).toBe('More filters')
    expect(toggle().attributes('aria-controls')).toBe('more-filters')
    expect(toggle().attributes('aria-expanded')).toBe('true')
    expect(toggle().attributes('aria-haspopup')).toBeUndefined()

    await toggle().trigger('click')
    expect(wrapper!.emitted('toggle')).toHaveLength(1)
  })

  it('announces a dialog instead of a panel', async () => {
    await mountAt('/events', { label: 'Filters', haspopup: 'dialog' })

    expect(toggle().attributes('aria-controls')).toBeUndefined()
    expect(toggle().attributes('aria-haspopup')).toBe('dialog')
    expect(toggle().attributes('aria-expanded')).toBe('false')
  })

  it('writes a submitted search to the URL', async () => {
    await mountAt('/venues')

    await wrapper!.get('input').setValue('club')
    await wrapper!.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query.q).toBe('club')
  })

  it('shows "Clear all" while something narrows the list, as the icon alone when compact', async () => {
    await mountAt('/venues?q=club', { compact: true })

    const clear = wrapper!.findAll('button').find((b) => b.attributes('aria-label') === 'Clear all')
    expect(clear?.text()).toBe('')
  })

  it('ends the row with the actions slot', async () => {
    await mountAt('/venues', {}, { actions: () => h('a', { href: '/map' }, 'Map') })

    expect(wrapper!.element.lastElementChild?.textContent).toBe('Map')
  })

  it('focuses the toggle on request', async () => {
    await mountAt('/events', { label: 'Filters' })

    wrapper!.vm.focusToggle()
    expect(document.activeElement).toBe(toggle().element)
  })
})
