import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const route = reactive<{ query: Record<string, string | string[]> }>({ query: {} })
const push = vi.fn<(location: unknown) => void>()

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))

const { default: EventTypeFilter } = await import('@/components/EventTypeFilter.vue')

function checkbox(label: string): HTMLInputElement {
  const found = [...document.body.querySelectorAll('label')].find(
    (it) => it.textContent?.trim() === label,
  )
  if (!found) throw new Error(`no checkbox labelled ${label}`)
  return found.querySelector('input') as HTMLInputElement
}

describe('EventTypeFilter', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    route.query = {}
    push.mockClear()
  })

  afterEach(() => {
    wrapper.unmount()
  })

  async function open(query: Record<string, string | string[]>) {
    route.query = query
    wrapper = mount(EventTypeFilter, { attachTo: document.body })
    await wrapper.get('[data-testid="event-type-filter"]').trigger('click')
    await flushPromises()
  }

  it('names the selection on its trigger', async () => {
    await open({})
    expect(wrapper.text()).toContain('All types')

    route.query = { eventType: 'PARTY' }
    await flushPromises()
    expect(wrapper.text()).toContain('Party')

    route.query = { eventType: ['CONCERT', 'PARTY'] }
    await flushPromises()
    expect(wrapper.text()).toContain('2 types')
  })

  it('adds a ticked type to the URL in list order', async () => {
    await open({ eventType: 'PARTY' })
    expect(checkbox('Party').checked).toBe(true)

    const concert = checkbox('Concert')
    concert.checked = true
    concert.dispatchEvent(new Event('change'))

    expect(push).toHaveBeenCalledWith({ query: { eventType: ['CONCERT', 'PARTY'] } })
  })

  it('clears every type', async () => {
    await open({ eventType: ['CONCERT', 'PARTY'], venue: 'lido' })
    const clear = [...document.body.querySelectorAll('button')].find(
      (it) => it.textContent?.trim() === 'Clear types',
    )
    clear?.click()

    expect(push).toHaveBeenCalledWith({ query: { venue: 'lido' } })
  })
})
