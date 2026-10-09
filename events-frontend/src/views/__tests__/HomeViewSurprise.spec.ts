import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: HomeView } = await import('@/views/HomeView.vue')
const { todayIso } = await import('@/lib/format')
const { i18n } = await import('@/i18n')
const { SMALL_ROOM_CAPACITY } = await import('@/lib/surpriseMe')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
  vi.unstubAllGlobals()
  i18n.global.locale.value = 'en'
})

const event = (slug: string, capacity: number | null) => ({
  slug,
  title: slug,
  eventDate: todayIso(),
  status: 'SCHEDULED',
  venue: {
    id: 1,
    slug: `venue-${slug}`,
    name: `Venue ${slug}`,
    city: 'Berlin',
    imageSources: [],
    capacity,
  },
})

/** A viewport below `sm`, where a phone is. */
function stubPhone() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: true,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

async function mountWith(tonight: unknown[], locale: 'en' | 'de' = 'en') {
  getMock.mockImplementation((path: string) =>
    Promise.resolve(path === '/api/events/today' ? tonight : { content: [] }),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: Page }],
  })
  await router.push(`/${locale}/`)
  wrapper = mount(HomeView, { global: { plugins: [router] } })
  await flushPromises()
  return { view: wrapper, router }
}

const surpriseButton = (view: VueWrapper) =>
  view.find<HTMLButtonElement>(`button[title*="${SMALL_ROOM_CAPACITY}"]`)

const smallRoomTonight = () => [event('one', 120)]

describe('HomeView "Surprise me" (#2722)', () => {
  it('is hidden when no event tonight is in a small room', async () => {
    const { view } = await mountWith([event('big', 1500), event('unknown', null)])

    expect(surpriseButton(view).exists()).toBe(false)
  })

  it('opens a small-room event, and a second press opens another', async () => {
    const { view, router } = await mountWith([
      event('big', 1500),
      event('one', 120),
      event('two', 80),
    ])

    const button = surpriseButton(view)
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()
    const first = router.currentRoute.value.path
    expect(['/en/events/one', '/en/events/two']).toContain(first)

    await button.trigger('click')
    await flushPromises()
    const second = router.currentRoute.value.path
    expect(['/en/events/one', '/en/events/two']).toContain(second)
    expect(second).not.toBe(first)
  })

  // The name comes from the label's text, which stays in the button below `sm` as `sr-only`: no
  // `aria-label` to drift from what sighted users read from `sm` up.
  it.each([
    ['en', 'Surprise me'],
    ['de', 'Überrasch mich'],
  ] as const)('is named %s "%s" on a phone and from sm up', async (locale, name) => {
    i18n.global.locale.value = locale
    for (const phone of [false, true]) {
      if (phone) stubPhone()
      const { view } = await mountWith(smallRoomTonight(), locale)
      const button = surpriseButton(view)

      expect(button.attributes('aria-label')).toBeUndefined()
      expect(button.attributes('aria-labelledby')).toBeUndefined()
      expect(button.text()).toBe(name)
      view.unmount()
      wrapper = undefined
    }
  })

  it('shows only the dice on a phone, with the label kept for screen readers', async () => {
    stubPhone()
    const { view } = await mountWith(smallRoomTonight())
    const button = surpriseButton(view)
    const label = button.find('span')

    expect(button.classes()).toContain('size-8')
    expect(label.text()).toBe('Surprise me')
    expect(label.classes()).toEqual(expect.arrayContaining(['sr-only', 'sm:not-sr-only']))
  })

  it('keeps the small labelled button from sm up', async () => {
    const { view } = await mountWith(smallRoomTonight())
    const button = surpriseButton(view)

    expect(button.classes()).toContain('h-7')
    expect(button.classes()).not.toContain('size-8')
  })
})
