import { afterEach, describe, expect, it } from 'vitest'

import { mount, type VueWrapper } from '@vue/test-utils'
import EventMetaLine from '@/components/EventMetaLine.vue'

describe('EventMetaLine', () => {
  const items = [{ text: 'Mi., 7. Okt. 2026' }, { text: '20:00' }, { text: 'Kantine am Berghain' }]

  it('puts one separator between two items, none first or last', () => {
    const wrapper = mount(EventMetaLine, { props: { items } })
    const children = wrapper.find('p').element.children
    const separators = wrapper.findAll('[data-meta-separator]')

    expect(separators).toHaveLength(2)
    expect(children[0]!.hasAttribute('data-meta-separator')).toBe(false)
    expect(children[children.length - 1]!.hasAttribute('data-meta-separator')).toBe(false)
    separators.forEach((separator) => expect(separator.attributes('aria-hidden')).toBe('true'))
  })

  it('keeps the dot on the line of the item before it', () => {
    const wrapper = mount(EventMetaLine, { props: { items } })

    expect(wrapper.text()).toBe('Mi., 7. Okt. 2026 · 20:00 · Kantine am Berghain')
  })

  it('reads with a space between two items once the dots are hidden', () => {
    const wrapper = mount(EventMetaLine, { props: { items } })
    const read = wrapper.find('p').element.cloneNode(true) as HTMLElement
    read.querySelectorAll('[aria-hidden="true"]').forEach((hidden) => hidden.remove())

    expect(read.textContent).toBe('Mi., 7. Okt. 2026 20:00 Kantine am Berghain')
  })

  it('renders a status as a pill and keeps a state word coloured', () => {
    const wrapper = mount(EventMetaLine, {
      props: {
        items: [
          { text: 'Fr., 9. Okt.' },
          { text: 'Abgesagt', badge: true },
          { text: 'Eintritt frei', class: 'text-success' },
        ],
      },
    })

    expect(wrapper.find('.rounded-full').text()).toBe('Abgesagt')
    expect(wrapper.find('span.text-success').text()).toBe('Eintritt frei')
    expect(wrapper.findAll('[data-meta-separator]')).toHaveLength(2)
  })

  describe('a derived genre', () => {
    let wrapper: VueWrapper | undefined
    afterEach(() => {
      wrapper?.unmount()
      wrapper = undefined
    })

    const note = 'Genre from the event description'
    /** The popover renders into a portal, so its content is looked up on the document. */
    const openNotes = () =>
      [...document.querySelectorAll('[data-meta-note]')].map((n) => n.textContent?.trim())

    it('opens its note on a tap, since a phone never shows a title', async () => {
      wrapper = mount(EventMetaLine, {
        props: { items: [{ text: 'Jazz' }, { text: 'Punk', note }] },
        attachTo: document.body,
      })
      const button = wrapper.get('button[data-meta-derived]')

      expect(button.attributes('type')).toBe('button')
      expect(button.attributes('title')).toBeUndefined()
      expect(openNotes()).toEqual([])

      await button.trigger('click')

      expect(openNotes()).toEqual([note])
      expect(button.attributes('aria-expanded')).toBe('true')
    })

    it('keeps the note in its name for a screen reader', () => {
      wrapper = mount(EventMetaLine, { props: { items: [{ text: 'Punk', note }] } })

      expect(wrapper.get('button').text()).toBe(`Punk (${note})`)
      expect(wrapper.get('button .sr-only').text()).toBe(`(${note})`)
    })

    it('leaves a source genre plain text, with no button', () => {
      wrapper = mount(EventMetaLine, { props: { items: [{ text: 'Jazz' }, { text: 'Techno' }] } })

      expect(wrapper.find('button').exists()).toBe(false)
      expect(wrapper.text()).toBe('Jazz\u00a0· Techno')
    })

    it('gives each of several derived genres its own note', async () => {
      const wikidata = 'Genre of the acts from Wikidata'
      wrapper = mount(EventMetaLine, {
        props: {
          items: [
            { text: 'Punk', note },
            { text: 'Schlager', note: wikidata },
          ],
        },
        attachTo: document.body,
      })
      const buttons = wrapper.findAll('button[data-meta-derived]')
      expect(buttons).toHaveLength(2)

      await buttons[1]!.trigger('click')

      expect(openNotes()).toEqual([wikidata])
    })

    it('keeps the dot outside the button, on the line before it', () => {
      wrapper = mount(EventMetaLine, {
        props: { items: [{ text: 'Jazz' }, { text: 'Punk', note }] },
      })
      const read = wrapper.find('p').element.cloneNode(true) as HTMLElement
      read.querySelectorAll('.sr-only').forEach((hidden) => hidden.remove())

      expect(read.textContent).toBe('Jazz\u00a0· Punk')
      expect(wrapper.get('button').find('[data-meta-separator]').exists()).toBe(false)
    })
  })
})
