import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'

const options = [
  { value: 'CONCERT', label: 'Concert' },
  { value: 'FESTIVAL', label: 'Festival' },
  { value: 'PARTY', label: 'Party' },
]

function checkbox(label: string): HTMLInputElement {
  const found = [...document.body.querySelectorAll('label')].find(
    (it) => it.textContent?.trim() === label,
  )
  if (!found) throw new Error(`no checkbox labelled ${label}`)
  return found.querySelector('input') as HTMLInputElement
}

describe('MultiSelectFilter', () => {
  let wrapper: VueWrapper

  afterEach(() => {
    wrapper.unmount()
  })

  async function open(selected: string[]) {
    wrapper = mount(MultiSelectFilter, {
      attachTo: document.body,
      props: {
        options,
        selected,
        label: 'Filter by event type',
        allLabel: 'All types',
        countLabel: (n: number) => `${n} types`,
        clearLabel: 'Clear types',
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

  it('clears every value', async () => {
    await open(['CONCERT', 'PARTY'])
    const clear = [...document.body.querySelectorAll('button')].find(
      (it) => it.textContent?.trim() === 'Clear types',
    )
    clear?.click()

    expect(wrapper.emitted('change')).toEqual([[[]]])
  })
})
