import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

const TAGS = [
  { slug: 'house', name: 'House', family: 'electronic' },
  {
    slug: 'progressive-psytrance',
    name: 'Progressive Psytrance and Full-On Goa Trance',
    family: 'electronic',
  },
  { slug: 'rap', name: 'Rap', family: 'hip-hop' },
  { slug: 'techno', name: 'Techno', family: 'electronic' },
  { slug: 'untagged', name: 'Untagged' },
]

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (path: string) => Promise.resolve(path === '/api/genres' ? TAGS : []) },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: EventFilterBar } = await import('@/components/EventFilterBar.vue')
const { default: MultiSelectFilter } = await import('@/components/MultiSelectFilter.vue')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined
let router: Router

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(EventFilterBar, { global: { plugins: [router] } })
  await flushPromises()
}

function filter(label: string) {
  return wrapper!
    .findAllComponents(MultiSelectFilter)
    .find((candidate) => candidate.props('label') === label)
}

const styles = () => filter('Filter by style')
const families = () => filter('Filter by genre')!

function moreFilters() {
  return wrapper!.findAll('button').find((candidate) => candidate.text().startsWith('More filters'))
}

describe('EventFilterBar styles (#3980)', () => {
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('offers the styles of the one chosen family as a multi-select', async () => {
    await mountAt('/events?family=electronic')
    expect(styles()!.props('options')).toEqual([
      { value: 'house', label: 'House' },
      {
        value: 'progressive-psytrance',
        label: 'Progressive Psytrance and Full-On Goa Trance',
      },
      { value: 'techno', label: 'Techno' },
    ])
    expect(styles()!.props('selected')).toEqual([])
  })

  it('writes the chosen styles to the URL as a repeated parameter', async () => {
    await mountAt('/events?family=electronic&genre=techno')
    styles()!.vm.$emit('change', ['house', 'techno'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      family: 'electronic',
      genre: ['house', 'techno'],
    })
  })

  it('reads a style-only link with two styles of one family as that family, both ticked', async () => {
    await mountAt('/events?genre=techno&genre=house')
    expect(families().props('selected')).toEqual(['electronic'])
    expect(styles()!.props('selected')).toEqual(['techno', 'house'])
    expect(moreFilters()!.text()).toBe('More filters (1)')
  })

  it('shows both families of a hand-built link across families, and no style control', async () => {
    await mountAt('/events?genre=techno&genre=rap')
    expect(families().props('selected')).toEqual(['electronic', 'hip-hop'])
    expect(styles()).toBeUndefined()
  })

  it('drops every style when a second family is chosen, and hides the control', async () => {
    await mountAt('/events?family=electronic&genre=techno&genre=house&district=mitte')
    families().vm.$emit('change', ['electronic', 'hip-hop'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      family: ['electronic', 'hip-hop'],
      district: 'mitte',
    })
    expect(styles()).toBeUndefined()
  })

  it('drops every style when the family changes on a style-only link', async () => {
    await mountAt('/events?genre=techno&genre=rap')
    families().vm.$emit('change', ['hip-hop'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ family: ['hip-hop'] })
  })

  it('names a long single style whole on the trigger, and several as a count', async () => {
    await mountAt('/events?family=electronic&genre=progressive-psytrance')
    expect(styles()!.get('button').attributes('aria-label')).toBe(
      'Filter by style: Progressive Psytrance and Full-On Goa Trance',
    )
    await router.push('/events?family=electronic&genre=techno&genre=house')
    await flushPromises()
    expect(styles()!.get('button').text()).toBe('2 styles')
  })
})
