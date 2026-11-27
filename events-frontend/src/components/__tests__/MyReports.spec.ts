import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({ fetchReportList: vi.fn<() => Promise<unknown[]>>() }))
vi.mock('@/api/account', () => account)

const { default: MyReports } = await import('@/components/MyReports.vue')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

beforeEach(() => vi.resetAllMocks())
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountIt() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: Page }],
  })
  await router.push('/en/account')
  wrapper = mount(MyReports, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('MyReports', () => {
  it('lists each open report with a link to its page, its kind, its date and the ticked boxes', async () => {
    account.fetchReportList.mockResolvedValue([
      {
        kind: 'venues',
        slug: 'sisyphos',
        title: 'Sisyphos',
        problems: ['ADDRESS', 'OTHER'],
        note: 'Moved',
        updatedAt: '2026-11-20T10:00:00Z',
      },
      {
        kind: 'events',
        slug: 'night',
        title: 'Night',
        problems: ['GENRE', 'ON_IMAGE'],
        updatedAt: '2026-11-21T10:00:00Z',
      },
    ])
    const view = await mountIt()

    const rows = view.findAll('li')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.get('a').attributes('href')).toBe('/en/venues/sisyphos')
    expect(rows[0]!.text()).toContain('Venue · November 20, 2026')
    expect(rows[0]!.text()).toContain('Address or map pin wrong, Note only')
    // The hint is no problem of its own.
    expect(rows[1]!.text()).toContain('Genre wrong or missing')
    expect(rows[1]!.text()).not.toContain('image')
    expect(view.text()).toContain('the bell tells you')
  })

  it('says how to report when there is nothing open', async () => {
    account.fetchReportList.mockResolvedValue([])
    const view = await mountIt()

    expect(view.find('li').exists()).toBe(false)
    expect(view.text()).toContain('No open reports.')
  })

  it('says so when the list does not load', async () => {
    account.fetchReportList.mockRejectedValue(new Error('offline'))
    const view = await mountIt()

    expect(view.text()).toContain('Could not load your reports.')
  })
})
