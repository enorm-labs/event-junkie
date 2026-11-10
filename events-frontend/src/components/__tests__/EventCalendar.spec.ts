import { afterEach, describe, expect, it, vi } from 'vitest'

import FullCalendar from '@fullcalendar/vue3'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import EventCalendar from '@/components/EventCalendar.vue'
import { i18n } from '@/i18n'
import { addDays, formatLongDate, todayIso } from '@/lib/format'

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

  it.each(['en', 'de'] as const)(
    'names every %s toolbar button by its visible words',
    async (locale) => {
      const wrapper = await render(locale)

      // axe's label-content-name-mismatch matches whole words, so "Monatsansicht" does not name "Monat".
      const labelled = wrapper.findAll('button').filter((button) => button.text() !== '')
      expect(labelled.length).toBeGreaterThan(1)
      for (const button of labelled) {
        const words = (button.attributes('aria-label') ?? '').split(/\s+/)
        expect(words).toEqual(expect.arrayContaining(button.text().split(/\s+/)))
      }
    },
  )

  it('keeps the English view names the e2e suite selects by', async () => {
    const wrapper = await render('en')

    const names = wrapper.findAll('button').map((button) => button.attributes('aria-label'))
    expect(names).toEqual(expect.arrayContaining(['Today', 'Month view', 'Week view', 'List view']))
  })

  it('shows an event time on the 24-hour clock', async () => {
    const wrapper = await render('de')

    expect(wrapper.text()).toContain('18:00')
    expect(wrapper.text()).not.toMatch(/\b6p\b/)
  })

  it.each([
    ['en', '+2 more on'],
    ['de', '+2 weitere am'],
  ] as const)('names the %s "+N more" button by its day (#4746)', async (locale, words) => {
    i18n.global.locale.value = locale
    const day = todayIso()
    const crowded = Array.from({ length: 8 }, (_, i) => ({
      title: `Night ${i}`,
      start: `${day}T${String(10 + i)}:00:00`,
    }))
    const wrapper = mount(EventCalendar, { props: { events: crowded }, attachTo: document.body })
    await flushPromises()

    const more = wrapper.get('[aria-haspopup="dialog"]')
    expect(more.attributes('aria-label')).toBe(`${words} ${formatLongDate(day, locale)}`)
  })

  it('announces the new month, not the first one (#4747)', async () => {
    const wrapper = await render('en')
    const status = wrapper.get('[role="status"]')
    expect(status.text()).toBe('')

    wrapper.findComponent(FullCalendar).vm.getApi().next()
    await flushPromises()

    expect(status.text()).toBe(wrapper.findComponent(FullCalendar).vm.getApi().view.title)
    expect(status.text()).not.toBe('')
  })

  it('draws an estimated start apart from a stated one, and says why', async () => {
    const day = todayIso()
    const events = [
      { title: 'Stated', start: `${day}T19:00:00`, extendedProps: { slug: 'stated' } },
      {
        title: 'Guessed',
        start: `${day}T20:00:00`,
        extendedProps: { slug: 'guessed', estimated: true },
      },
    ]
    const wrapper = mount(EventCalendar, { props: { events }, attachTo: document.body })
    await flushPromises()

    const marked = wrapper.findAll('.ej-estimated')
    expect(marked.map((time) => time.text())).toEqual(['20:00'])
    const guessed = wrapper.findAll('.ej-event').find((el) => el.text().includes('Guessed'))
    expect(guessed?.attributes('title')).toContain('Not announced, estimated from the event type')
    expect(guessed?.find('.sr-only').text()).toBe('Not announced, estimated from the event type')
  })

  it.each([
    ['en', 'Ongoing'],
    ['de', 'Läuft'],
  ] as const)('labels a %s list day of a run as ongoing, not all-day', async (locale, words) => {
    i18n.global.locale.value = locale
    const events = [
      {
        title: 'Run',
        start: addDays(todayIso(), -3),
        end: addDays(todayIso(), 4),
        extendedProps: { slug: 'run' },
      },
    ]
    const wrapper = mount(EventCalendar, {
      props: { events, initialView: 'listWeek' },
      attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.text()).toContain(words)
    expect(wrapper.text()).not.toMatch(/all-day|ganztägig/i)
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

  it("places a day's own events before the runs that span it (#4655)", async () => {
    const wrapper = await render('en')

    expect(wrapper.findComponent(FullCalendar).props('options')!.eventOrder).toBe(
      'multiDay,start,title',
    )
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

  it('opens on the month from md up', async () => {
    stubNarrow(false)
    const wrapper = await render('en')

    expect(viewType(wrapper)).toBe('dayGridMonth')
  })

  it('opens on the week list below md, with the month a tab away (#4657)', async () => {
    stubNarrow(true)
    const wrapper = await render('en')

    expect(viewType(wrapper)).toBe('listWeekNarrow')
    expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text())).toContain('Month')
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
