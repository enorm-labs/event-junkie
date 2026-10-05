import { afterEach, describe, expect, it, vi } from 'vitest'

import FullCalendar from '@fullcalendar/vue3'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import EventCalendar from '@/components/EventCalendar.vue'
import { i18n } from '@/i18n'
import { todayIso } from '@/lib/format'

/** The calendar's chrome follows the page locale, and its times use the 24-hour clock (#2658). */

const tonight = [
  { title: 'A Night', start: `${todayIso()}T18:00:00`, extendedProps: { slug: 'a-night' } },
]

async function render(locale: 'en' | 'de') {
  i18n.global.locale.value = locale
  const wrapper = mount(EventCalendar, { props: { events: tonight }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('EventCalendar', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en'
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('labels the today button in German on a German page', async () => {
    const wrapper = await render('de')

    const buttons = wrapper.findAll('button').map((button) => button.text())
    expect(buttons).toContain('Heute')
    expect(buttons).not.toContain('Today')
  })

  it('shows an event time on the 24-hour clock', async () => {
    const wrapper = await render('de')

    expect(wrapper.text()).toContain('18:00')
    expect(wrapper.text()).not.toMatch(/\b6p\b/)
  })

  it('uses the 24-hour clock on an English page too', async () => {
    const wrapper = await render('en')

    expect(wrapper.findAll('button').map((button) => button.text())).toContain('Today')
    expect(wrapper.text()).toContain('18:00')
  })
})

/** A `matchMedia` whose one query starts at `matches` and can be flipped, as a resize would. */
function stubNarrow(matches: boolean) {
  const listeners: ((event: { matches: boolean }) => void)[] = []
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches,
      addEventListener: (_: string, listener: (event: { matches: boolean }) => void) =>
        listeners.push(listener),
      removeEventListener: () => {},
    })),
  )
  return (next: boolean) => listeners.forEach((listener) => listener({ matches: next }))
}

function viewType(wrapper: VueWrapper) {
  return wrapper.findComponent(FullCalendar).vm.getApi().view.type
}

async function openWeek(wrapper: VueWrapper) {
  const week = wrapper.findAll('[role="tab"]').find((tab) => tab.text() === 'Week')
  await week!.trigger('click')
  await flushPromises()
}

/** The week shows every event, as a grid from `md` up and as a day list below it (#2693). */
describe('EventCalendar week view', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('never folds a day of the week grid into "+N more"', async () => {
    const wrapper = await render('en')

    const views = wrapper.findComponent(FullCalendar).props('options')!.views!
    expect(views.dayGridWeek!.dayMaxEvents).toBe(false)
  })

  it('opens the week grid from the Week button on a wide screen', async () => {
    stubNarrow(false)
    const wrapper = await render('en')

    await openWeek(wrapper)

    expect(viewType(wrapper)).toBe('dayGridWeek')
  })

  it('opens the week as a day list from the Week button below md', async () => {
    stubNarrow(true)
    const wrapper = await render('en')

    await openWeek(wrapper)

    expect(viewType(wrapper)).toBe('listWeekNarrow')
    expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text())).toEqual([
      'Month',
      'Week',
      'List',
    ])
  })

  it('swaps an open week between grid and list when the width crosses md', async () => {
    const setNarrow = stubNarrow(false)
    const wrapper = await render('en')
    await openWeek(wrapper)

    setNarrow(true)
    await flushPromises()
    expect(viewType(wrapper)).toBe('listWeekNarrow')

    setNarrow(false)
    await flushPromises()
    expect(viewType(wrapper)).toBe('dayGridWeek')
  })
})
