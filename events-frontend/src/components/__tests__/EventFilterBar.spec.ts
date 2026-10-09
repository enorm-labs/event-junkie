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
const { default: MultiSelectFilter } = await import('@/components/MultiSelectFilter.vue')
const { tonight } = await import('@/lib/dateRanges')
const { i18n } = await import('@/i18n')

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

describe('EventFilterBar time of night', () => {
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  function timeFilter() {
    const found = wrapper!
      .findAllComponents(MultiSelectFilter)
      .find((candidate) => candidate.props('label') === 'Filter by time of night')
    if (!found) throw new Error('no time-of-night filter')
    return found
  }

  it('reads the slots from the URL, counts them once under More filters, and says what drops out', async () => {
    await mountAt('/events?timeOfDay=late&timeOfDay=daytime')
    expect(timeFilter().props('selected')).toEqual(['late', 'daytime'])
    expect(timeFilter().props('hint')).toBe(
      'An event matches every time it runs through; without an end, its start decides. Events with no time are left out.',
    )
    expect(button('More filters (1)').attributes('aria-expanded')).toBe('true')
  })

  it('writes the chosen slots to the URL', async () => {
    await mountAt('/events')
    timeFilter().vm.$emit('change', ['evening', 'late'])
    await flushPromises()
    expect(router.currentRoute.value.query.timeOfDay).toEqual(['evening', 'late'])
  })
})

describe('EventFilterBar spoken language', () => {
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  function filter(label: string) {
    return wrapper!
      .findAllComponents(MultiSelectFilter)
      .find((candidate) => candidate.props('label') === label)
  }

  const languageFilter = () => filter('Filter by spoken language')

  it('is not offered for concerts, nor without a type', async () => {
    await mountAt('/events?eventType=CONCERT')
    expect(languageFilter()).toBeUndefined()
    wrapper!.unmount()

    await mountAt('/events')
    expect(languageFilter()).toBeUndefined()
    wrapper!.unmount()

    await mountAt('/events?eventType=COMEDY&eventType=PARTY')
    expect(languageFilter()).toBeUndefined()
  })

  it('is offered for comedy, reads the URL and says what drops out', async () => {
    await mountAt('/events?eventType=COMEDY&language=en')
    expect(languageFilter()!.props('selected')).toEqual(['en'])
    expect(languageFilter()!.props('options')).toEqual([
      { value: 'en', label: 'English' },
      { value: 'de', label: 'German' },
    ])
    expect(languageFilter()!.props('hint')).toBe(
      'Only events whose listing names the language. Events that do not say are left out.',
    )
    expect(button('More filters (2)').attributes('aria-expanded')).toBe('true')
  })

  it('writes the chosen languages to the URL', async () => {
    await mountAt('/events?eventType=COMEDY')
    languageFilter()!.vm.$emit('change', ['en', 'de'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ eventType: 'COMEDY', language: ['en', 'de'] })
  })

  it('drops the language when the type filter leaves the types that carry one', async () => {
    await mountAt('/events?eventType=COMEDY&language=en')
    filter('Filter by event type')!.vm.$emit('change', ['COMEDY', 'CONCERT'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ eventType: ['COMEDY', 'CONCERT'] })
    expect(languageFilter()).toBeUndefined()
  })

  it('keeps the language when the types still carry one', async () => {
    await mountAt('/events?eventType=COMEDY&language=en')
    filter('Filter by event type')!.vm.$emit('change', ['READING', 'COMEDY'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      eventType: ['READING', 'COMEDY'],
      language: ['en'],
    })
  })
})

describe('EventFilterBar language note', () => {
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    i18n.global.locale.value = 'en'
  })

  const EN =
    'Only events whose listing names the language are shown; those that do not say are left out.'
  const DE =
    'Es erscheinen nur Events, deren Ankündigung die Sprache nennt; Events ohne Angabe fallen weg.'

  it('says what drops out while the language filter is set', async () => {
    await mountAt('/events?eventType=COMEDY&language=en')
    expect(wrapper!.text()).toContain(EN)
  })

  it('says it in German', async () => {
    i18n.global.locale.value = 'de'
    await mountAt('/events?eventType=COMEDY&language=en')
    expect(wrapper!.text()).toContain(DE)
  })

  it('is gone without a language, and when the type filter hides the control', async () => {
    await mountAt('/events?eventType=COMEDY')
    expect(wrapper!.text()).not.toContain(EN)
    wrapper!.unmount()

    await mountAt('/events?eventType=CONCERT&language=en')
    expect(wrapper!.text()).not.toContain(EN)
  })

  it('goes when the language is cleared', async () => {
    await mountAt('/events?eventType=COMEDY&language=en')
    await router.push('/events?eventType=COMEDY')
    await flushPromises()
    expect(wrapper!.text()).not.toContain(EN)
  })
})

describe('EventFilterBar on a phone', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: true,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.unstubAllGlobals()
  })

  async function mountNarrow(path: string, props: Record<string, unknown> = {}) {
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:rest(.*)', component: Page }],
    })
    await router.push(path)
    wrapper = mount(EventFilterBar, {
      props,
      global: { plugins: [router] },
      attachTo: document.body,
    })
    await flushPromises()
  }

  const dialog = () => document.body.querySelector('[role="dialog"]')

  it('keeps the second tier and the date inputs closed, even when the URL sets a filter', async () => {
    await mountNarrow('/events?timeOfDay=late')
    const toggle = button('Filters (1)')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-haspopup')).toBe('dialog')
    expect(wrapper!.find('#more-filters').exists()).toBe(false)
    expect(document.body.querySelectorAll('input[type="date"]')).toHaveLength(0)
    expect(dialog()).toBeNull()
  })

  it('opens the sheet with the date inputs and the panel, and closes it from its button', async () => {
    await mountNarrow('/events', { resultCount: 12 })
    await click('Filters')

    const sheet = dialog()!
    expect(sheet.textContent).toContain('Filters')
    expect(sheet.querySelectorAll('input[type="date"]')).toHaveLength(2)
    expect(sheet.querySelector('[aria-label="Filter by venue"]')).not.toBeNull()

    const show = [...sheet.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Show 12 events',
    )!
    show.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(button('Filters').attributes('aria-expanded')).toBe('false')
  })
})
