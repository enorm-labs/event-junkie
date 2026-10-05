import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import SearchInput from '@/components/SearchInput.vue'

let wrapper: VueWrapper | undefined
const onClear = vi.fn<() => void>()

/** A parent holding the draft, as the list pages do, so `v-model` round-trips. */
function mountWith(initial: string) {
  const draft = ref(initial)
  const Host = defineComponent({
    render: () =>
      h(SearchInput, {
        modelValue: draft.value,
        'onUpdate:modelValue': (value: string) => (draft.value = value),
        onClear,
        placeholder: 'Search venues…',
      }),
  })
  wrapper = mount(Host, { attachTo: document.body })
  return draft
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  onClear.mockReset()
})

describe('SearchInput', () => {
  it('shows no clear button while the field is empty', () => {
    mountWith('')
    expect(wrapper!.find('button').exists()).toBe(false)
    expect(wrapper!.get('input').attributes('type')).toBe('search')
  })

  it('offers an inline ✕ named "Clear search" once there is text', () => {
    mountWith('club')
    const clear = wrapper!.get('button')
    expect(clear.attributes('aria-label')).toBe('Clear search')
    expect(clear.attributes('type')).toBe('button')
  })

  it('empties the text only, emits clear, and returns focus to the field', async () => {
    const draft = mountWith('club')

    await wrapper!.get('button').trigger('click')

    expect(draft.value).toBe('')
    expect(onClear).toHaveBeenCalledOnce()
    expect(document.activeElement).toBe(wrapper!.get('input').element)
    expect(wrapper!.find('button').exists()).toBe(false)
  })

  it('passes attributes through to the input', () => {
    mountWith('')
    expect(wrapper!.get('input').attributes('placeholder')).toBe('Search venues…')
  })
})
