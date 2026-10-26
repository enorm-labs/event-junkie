import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  fetchSession: vi.fn<() => Promise<unknown>>(),
  fetchRsvps: vi.fn<() => Promise<Record<string, string>>>(),
  fetchRsvpUpcoming: vi.fn<(page?: number) => Promise<unknown>>(),
  fetchRsvpPast: vi.fn<(page?: number) => Promise<unknown>>(),
  fetchRsvpCalendar: vi.fn<(from: string, to: string) => Promise<unknown[]>>(),
}))
vi.mock('@/api/account', () => account)

const { default: MyCalendarView } = await import('@/views/account/MyCalendarView.vue')
const { setSession } = await import('@/composables/useSession')

const Page = defineComponent({ render: () => h('p') })
const Card = defineComponent({
  props: { event: { type: Object, required: true } },
  render() {
    return h('p', (this.event as { title: string }).title)
  },
})
// FullCalendar does not render in jsdom; the stub reports a window, as the real one does on mount.
const Calendar = defineComponent({
  emits: ['datesSet'],
  mounted() {
    this.$emit('datesSet', { from: '2026-10-26', to: '2026-12-07' })
  },
  render: () => h('div', { 'data-testid': 'calendar' }),
})
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
const event = (slug: string, title: string, eventDate: string) => ({ slug, title, eventDate })

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.fetchRsvps.mockResolvedValue({})
  account.fetchRsvpUpcoming.mockResolvedValue({
    content: [event('tonight', 'Tonight', '2026-10-26')],
    totalPages: 1,
  })
  account.fetchRsvpPast.mockResolvedValue({
    content: [event('last-month', 'Last month', '2026-09-26')],
    totalPages: 1,
  })
  account.fetchRsvpCalendar.mockResolvedValue([event('tonight', 'Tonight', '2026-10-26')])
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(MyCalendarView, {
    global: {
      plugins: [router],
      stubs: { EventCard: Card, EventRow: Card, EventCalendar: Calendar },
    },
  })
  await flushPromises()
  return wrapper
}

describe('MyCalendarView', () => {
  it('signed out, sends the visitor to sign-in and back', async () => {
    setSession(null)
    await mountAt('/en/my-calendar')
    expect(router.currentRoute.value.fullPath).toBe('/en/sign-in?next=/en/my-calendar')
  })

  it('lists what is coming up, then the history', async () => {
    setSession(me as never)
    const view = await mountAt('/en/my-calendar')

    expect(view.findAll('h2').map((h2) => h2.text())).toEqual(['Coming up', 'Been there'])
    expect(view.text()).toContain('Tonight')
    expect(view.text()).toContain('Last month')
  })

  it('shows the calendar, asks for its window, and keeps the view in the URL', async () => {
    setSession(me as never)
    const view = await mountAt('/en/my-calendar')

    await view
      .findAll('button')
      .find((b) => b.text() === 'Calendar')!
      .trigger('click')
    await flushPromises()

    expect(view.find('[data-testid="calendar"]').exists()).toBe(true)
    expect(account.fetchRsvpCalendar).toHaveBeenCalledWith('2026-10-26', '2026-12-07')
    expect(router.currentRoute.value.query.view).toBe('calendar')
  })
})
