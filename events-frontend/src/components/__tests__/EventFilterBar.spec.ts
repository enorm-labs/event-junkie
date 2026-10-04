import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: () => Promise.resolve([]) },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: EventFilterBar } = await import('@/components/EventFilterBar.vue')
const { tonight } = await import('@/lib/dateRanges')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined
let router: Router

async function mountAt(path: string, props: Record<string, unknown> = { showOnNow: true }) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(EventFilterBar, { props, global: { plugins: [router] } })
  await flushPromises()
}

function button(name: string) {
  const found = wrapper!.findAll('button').find((candidate) => candidate.text() === name)
  if (!found) throw new Error(`no button "${name}"`)
  return found
}

function pressed(name: string): string | undefined {
  return button(name).attributes('aria-pressed')
}

async function click(name: string) {
  await button(name).trigger('click')
  await flushPromises()
}

describe('EventFilterBar time options', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-07T18:00:00Z'))
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
  })

  it('puts On now first, before the presets', async () => {
    await mountAt('/events')
    const names = wrapper!.findAll('button').map((candidate) => candidate.text())
    expect(names.indexOf('On now')).toBeGreaterThanOrEqual(0)
    expect(names.indexOf('On now')).toBeLessThan(names.indexOf('Tonight'))
  })

  it('offers On now only to a view that opts in', async () => {
    await mountAt('/events', {})
    expect(wrapper!.findAll('button').some((candidate) => candidate.text() === 'On now')).toBe(
      false,
    )
  })

  it('removes the range when On now is pressed, and no preset stays pressed', async () => {
    await mountAt('/events?from=2026-10-09&to=2026-10-11&venue=lido')

    await click('On now')

    expect(router.currentRoute.value.query).toEqual({ now: '1', venue: 'lido' })
    expect(pressed('On now')).toBe('true')
    expect(pressed('This weekend')).toBe('false')
  })

  it('removes On now when a preset is pressed', async () => {
    await mountAt('/events?now=1&venue=lido')

    await click('Tonight')

    const { from, to } = tonight()
    expect(router.currentRoute.value.query).toEqual({ from, to, venue: 'lido' })
    expect(pressed('Tonight')).toBe('true')
    expect(pressed('On now')).toBe('false')
  })

  it('removes On now when a date is picked', async () => {
    await mountAt('/events?now=1')

    const input = wrapper!.get('input[aria-label="Earliest event date"]')
    await input.setValue('2026-10-20')
    await input.trigger('change')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ from: '2026-10-20' })
  })

  it('clears On now when it is pressed again', async () => {
    await mountAt('/events?now=1')

    await click('On now')

    expect(router.currentRoute.value.query).toEqual({})
    expect(pressed('On now')).toBe('false')
  })

  it("does not show the view's default range as pressed while On now is", async () => {
    await mountAt('/map?now=1', { showOnNow: true, defaultRange: tonight() })

    expect(pressed('On now')).toBe('true')
    expect(pressed('Tonight')).toBe('false')
  })
})
