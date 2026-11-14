import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { type Component, defineComponent, h } from 'vue'

vi.mock('@/components/VenueMap.vue', () => ({
  default: defineComponent({
    name: 'VenueMap',
    props: { pins: { type: Array, default: () => [] } },
    setup: (props) => () => h('div', { 'data-testid': 'map', 'data-pins': props.pins.length }),
  }),
  __esModule: true,
}))

const { LazyVenueMap } = await import('@/components/lazyVenueMap')

describe('LazyVenueMap', () => {
  it("holds the map's height until the map arrives (#5026)", async () => {
    // Inside a parent, as in a view: a wrapper around the async component itself keeps its first element.
    const wrapper = mount(
      defineComponent({ render: () => h(LazyVenueMap as Component, { pins: [{ slug: 'a' }] }) }),
    )

    const box = wrapper.get('[aria-hidden="true"]')
    expect(box.classes()).toEqual(expect.arrayContaining(['h-112', 'lg:h-144']))
    expect(box.attributes('pins')).toBeUndefined()

    await vi.waitFor(() => expect(wrapper.find('[data-testid="map"]').exists()).toBe(true))
    expect(wrapper.find('[aria-hidden="true"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="map"]').attributes('data-pins')).toBe('1')
  })
})
