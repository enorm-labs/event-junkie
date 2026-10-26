import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'

const options = [
  { value: 'CONCERT', label: 'Concert' },
  { value: 'FESTIVAL', label: 'Festival' },
  { value: 'PARTY', label: 'Party' },
]

/** The checkbox whose label starts with `label`; a count may follow it. */
function checkbox(label: string): HTMLInputElement {
  const found = [...document.body.querySelectorAll('label')].find((it) =>
    new RegExp(`^${label}(\\s+\\d+)?$`).test(it.textContent?.trim() ?? ''),
  )
  if (!found) throw new Error(`no checkbox labelled ${label}`)
  return found.querySelector('input') as HTMLInputElement
}

describe('MultiSelectFilter', () => {
  let wrapper: VueWrapper

  afterEach(() => {
    wrapper.unmount()
  })

  async function open(selected: string[], hint?: string, counts?: Record<string, number>) {
    wrapper = mount(MultiSelectFilter, {
      attachTo: document.body,
      props: {
        options,
        selected,
        label: 'Filter by event type',
        allLabel: 'All types',
        countLabel: (n: number) => `${n} types`,
        clearLabel: 'Clear types',
        hint,
        counts,
      },
    })
    await wrapper.get('button').trigger('click')
    await flushPromises()
  }

  it('names the selection on its trigger', async () => {
    await open([])
    const trigger = wrapper.get('button')
    expect(trigger.text()).toBe('All types')
    expect(trigger.attributes('aria-label')).toBe('Filter by event type: All types')

    await wrapper.setProps({ selected: ['PARTY'] })
    expect(trigger.text()).toBe('Party')

    await wrapper.setProps({ selected: ['PARTY', 'CONCERT'] })
    expect(trigger.text()).toBe('2 types')
  })

  it('emits the new selection in option order, not tick order', async () => {
    await open(['PARTY'])
    expect(checkbox('Party').checked).toBe(true)

    const concert = checkbox('Concert')
    concert.checked = true
    concert.dispatchEvent(new Event('change'))

    expect(wrapper.emitted('change')).toEqual([[['CONCERT', 'PARTY']]])
  })

  it('describes the options with its hint, and has no description without one', async () => {
    await open([], 'Only venues with every type you tick are shown.')
    const fieldset = document.body.querySelector('fieldset') as HTMLFieldSetElement
    const hint = document.getElementById(fieldset.getAttribute('aria-describedby') ?? '')
    expect(hint?.textContent).toBe('Only venues with every type you tick are shown.')
    wrapper.unmount()

    await open([])
    expect(document.body.querySelector('fieldset')?.hasAttribute('aria-describedby')).toBe(false)
  })

  it('shows each count and disables an option that leaves nothing, unless it is selected', async () => {
    await open(['PARTY'], undefined, { PARTY: 4, CONCERT: 2 })

    const texts = [...document.body.querySelectorAll('label')].map((it) =>
      it.textContent?.replace(/\s+/g, ' ').trim(),
    )
    expect(texts).toEqual(['Concert 2', 'Festival 0', 'Party 4'])
    expect(checkbox('Concert').disabled).toBe(false)
    expect(checkbox('Festival').disabled).toBe(true)

    await wrapper.setProps({ counts: { CONCERT: 2 } })
    expect(checkbox('Party').checked).toBe(true)
    expect(checkbox('Party').disabled).toBe(false)
  })

  it('keeps option order without search, whatever is ticked', async () => {
    await open(['PARTY'])
    const texts = [...document.body.querySelectorAll('label')].map((it) => it.textContent?.trim())
    expect(texts).toEqual(['Concert', 'Festival', 'Party'])
  })

  it('enables every option and shows no count without counts', async () => {
    await open([])
    expect(checkbox('Festival').disabled).toBe(false)
    expect(document.body.querySelector('label')?.textContent?.trim()).toBe('Concert')
  })

  it('clears every value', async () => {
    await open(['CONCERT', 'PARTY'])
    const clear = [...document.body.querySelectorAll('button')].find(
      (it) => it.textContent?.trim() === 'Clear types',
    )
    clear?.click()

    expect(wrapper.emitted('change')).toEqual([[[]]])
  })

  it('closes the list on Done without changing the selection', async () => {
    await open(['PARTY'])
    const done = [...document.body.querySelectorAll('button')].find(
      (it) => it.textContent?.trim() === 'Done',
    )
    done?.click()
    await flushPromises()

    expect(document.body.querySelector('fieldset')).toBeNull()
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  describe('with search (#3979)', () => {
    const venues = [
      { value: 'astra', label: 'Astra Kulturhaus' },
      { value: 'saelchen', label: 'Säälchen' },
      { value: 'lido', label: 'Lido' },
      {
        value: 'kulturbrauerei',
        label: 'Kulturbrauerei — Maschinenhaus & Kesselhaus im Hof der alten Schultheiss-Brauerei',
      },
    ]

    async function openSearch(selected: string[], options = venues) {
      wrapper = mount(MultiSelectFilter, {
        attachTo: document.body,
        props: {
          options,
          selected,
          label: 'Filter by venue',
          allLabel: 'All venues',
          countLabel: (n: number) => `${n} venues`,
          clearLabel: 'Clear venues',
          searchable: true,
          searchLabel: 'Search venues',
          noMatchLabel: 'No venue matches.',
        },
      })
      await wrapper.get('button').trigger('click')
      await flushPromises()
    }

    function field(): HTMLInputElement {
      return document.body.querySelector('input[type="search"]') as HTMLInputElement
    }

    async function type(text: string) {
      field().value = text
      field().dispatchEvent(new Event('input'))
      await flushPromises()
    }

    const listed = () =>
      [...document.body.querySelectorAll('label')].map((it) => it.textContent?.trim())

    it('has a labelled search field only when searchable', async () => {
      await openSearch([])
      expect(field().getAttribute('aria-label')).toBe('Search venues')
      wrapper.unmount()

      await open([])
      expect(field()).toBeNull()
    })

    it('narrows by name, ignoring case and accents', async () => {
      await openSearch([])
      await type('SAAL')
      expect(listed()).toEqual(['Säälchen'])

      await type('kultur')
      expect(listed()).toEqual([
        'Astra Kulturhaus',
        'Kulturbrauerei — Maschinenhaus & Kesselhaus im Hof der alten Schultheiss-Brauerei',
      ])
    })

    it('keeps a ticked option listed whatever the query, and ticking survives a new query', async () => {
      await openSearch(['lido'])
      await type('astra')
      expect(listed()).toEqual(['Lido', 'Astra Kulturhaus'])

      const astra = checkbox('Astra Kulturhaus')
      astra.checked = true
      astra.dispatchEvent(new Event('change'))
      expect(wrapper.emitted('change')).toEqual([[['astra', 'lido']]])

      await wrapper.setProps({ selected: ['astra', 'lido'] })
      await type('saal')
      expect(listed()).toEqual(['Lido', 'Astra Kulturhaus', 'Säälchen'])
      expect(checkbox('Lido').checked).toBe(true)
    })

    it('lists the options ticked on opening first, each group in option order', async () => {
      await openSearch(['kulturbrauerei', 'saelchen'])
      expect(listed()).toEqual(['Säälchen', venues[3]!.label, 'Astra Kulturhaus', 'Lido'])
    })

    it('keeps the order while open, and takes the new one on reopening', async () => {
      await openSearch(['lido'])
      const astra = checkbox('Astra Kulturhaus')
      astra.checked = true
      astra.dispatchEvent(new Event('change'))
      await wrapper.setProps({ selected: ['astra'] })
      expect(listed()).toEqual(['Lido', 'Astra Kulturhaus', 'Säälchen', venues[3]!.label])

      const done = [...document.body.querySelectorAll('button')].find(
        (it) => it.textContent?.trim() === 'Done',
      )
      done?.click()
      await flushPromises()
      await wrapper.get('button').trigger('click')
      await flushPromises()
      expect(listed()).toEqual(['Astra Kulturhaus', 'Säälchen', 'Lido', venues[3]!.label])
    })

    it('says when nothing matches', async () => {
      await openSearch([])
      await type('berghain')
      expect(listed()).toEqual([])
      expect(document.body.textContent).toContain('No venue matches.')
    })

    it('clears the query when the list closes', async () => {
      await openSearch([])
      await type('saal')
      const done = [...document.body.querySelectorAll('button')].find(
        (it) => it.textContent?.trim() === 'Done',
      )
      done?.click()
      await flushPromises()

      await wrapper.get('button').trigger('click')
      await flushPromises()
      expect(field().value).toBe('')
      expect(listed()).toHaveLength(4)
    })

    it('names a long single choice whole on its trigger, and many as a count', async () => {
      await openSearch(['kulturbrauerei'])
      expect(wrapper.get('button').text()).toBe(venues[3]!.label)

      await wrapper.setProps({ selected: ['astra', 'saelchen', 'lido', 'kulturbrauerei'] })
      expect(wrapper.get('button').text()).toBe('4 venues')
    })

    it('names a venue the list has not loaded by its slug', async () => {
      await openSearch(['lido'], [])
      expect(wrapper.get('button').text()).toBe('lido')
    })
  })
})
