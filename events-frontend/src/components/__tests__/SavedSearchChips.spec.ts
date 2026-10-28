import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import SavedSearchChips from '@/components/SavedSearchChips.vue'

const searches = [
  { id: 'a', name: 'Free techno', query: 'free=true&genre=techno' },
  { id: 'b', name: 'Jazz', query: 'genre=jazz' },
]

async function mountChips(props: { searches: typeof searches; counts: Record<string, number> }) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push('/en/following')
  return mount(SavedSearchChips, { props, global: { plugins: [router] } })
}

describe('SavedSearchChips', () => {
  it('links each search to the list with its filters, with its count once known', async () => {
    const view = await mountChips({ searches, counts: { a: 12 } })

    expect(view.find('ul').attributes('aria-label')).toBe('Your saved searches')
    const links = view.findAll('a')
    expect(links.map((a) => a.attributes('href'))).toEqual([
      '/en/events?free=true&genre=techno',
      '/en/events?genre=jazz',
    ])
    expect(links[0]!.text()).toBe('Free techno12, 12 upcoming')
    expect(links[1]!.text()).toBe('Jazz')
  })

  it('shows six, then one link to the follows tab with the rest', async () => {
    const many = Array.from({ length: 20 }, (_, i) => ({
      id: `s${i}`,
      name: `Search ${i}`,
      query: 'genre=jazz',
    }))
    const view = await mountChips({ searches: many, counts: {} })

    const links = view.findAll('a')
    expect(links).toHaveLength(7)
    expect(links[5]!.text()).toBe('Search 5')
    expect(links[6]!.text()).toBe('All 20 saved searches')
    expect(links[6]!.attributes('href')).toBe('/en/following?tab=follows')
  })

  it('keeps a long name whole in the title', async () => {
    const name = 'Free techno and house in Neukölln, Kreuzberg and Friedrichshain this weekend'
    const view = await mountChips({ searches: [{ id: 'a', name, query: 'free=true' }], counts: {} })

    expect(view.find('a').attributes('title')).toBe(name)
    expect(view.find('a').text()).toBe(name)
  })

  it('renders nothing without saved searches', async () => {
    const view = await mountChips({ searches: [], counts: {} })
    expect(view.find('ul').exists()).toBe(false)
  })
})
