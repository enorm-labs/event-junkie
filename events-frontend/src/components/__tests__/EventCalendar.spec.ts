import { afterEach, describe, expect, it } from 'vitest'

import { flushPromises, mount } from '@vue/test-utils'
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
