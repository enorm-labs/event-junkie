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
})
