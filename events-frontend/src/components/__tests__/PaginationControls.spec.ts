import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import PaginationControls from '@/components/PaginationControls.vue'

const Page = defineComponent({ render: () => h('p') })

async function mountAt(currentPage: number, totalPages: number) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/en/venues', component: Page }],
  })
  await router.push('/en/venues?district=mitte')
  const pageLink = (page: number) => ({
    query: page > 0 ? { district: 'mitte', page: String(page) } : { district: 'mitte' },
  })
  const wrapper = mount(PaginationControls, {
    props: { currentPage, totalPages, pageLink },
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

describe('PaginationControls', () => {
  it('renders nothing on a single page', async () => {
    expect((await mountAt(0, 1)).find('nav').exists()).toBe(false)
  })

  it('links every page it names, marks the current one and keeps the filters', async () => {
    const wrapper = await mountAt(9, 22)
    const numbers = wrapper.findAll('ol a')

    expect(numbers.map((link) => link.text())).toEqual(['1', '9', '10', '11', '22'])
    expect(wrapper.get('[aria-current="page"]').text()).toBe('10')
    expect(numbers[0]!.attributes('href')).toBe('/en/venues?district=mitte')
    expect(wrapper.get('a[rel="next"]').attributes('href')).toBe(
      '/en/venues?district=mitte&page=10',
    )
    expect(wrapper.get('a[rel="prev"]').attributes('href')).toBe('/en/venues?district=mitte&page=8')
  })

  it('disables Previous on the first page and Next on the last', async () => {
    const first = await mountAt(0, 3)
    expect(first.find('a[rel="prev"]').exists()).toBe(false)
    expect(first.get('button:disabled').text()).toBe('Previous')

    const last = await mountAt(2, 3)
    expect(last.find('a[rel="next"]').exists()).toBe(false)
    expect(last.get('button:disabled').text()).toBe('Next')
  })

  it('reads "Page n of m" where the numbers have no room', async () => {
    expect((await mountAt(4, 22)).text()).toContain('Page 5 of 22')
  })
})
