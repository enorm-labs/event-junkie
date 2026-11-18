import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useCalendarReturn } from '@/composables/useCalendarReturn'

type Memory = ReturnType<typeof useCalendarReturn>

/** Mounts a page that uses the memory, as CalendarView does, and hands the memory back. */
function page(scope = 'q') {
  let memory!: Memory
  const wrapper = mount(
    defineComponent({
      setup() {
        memory = useCalendarReturn('test', () => scope)
        memory.keepOnLeave(() => [{ title: 'Gig' }])
        return () => h('div')
      },
    }),
  )
  return { wrapper, memory }
}

const place = { from: '2026-11-23', to: '2026-11-29', view: 'listWeek', date: '2026-11-23' }

describe('useCalendarReturn', () => {
  afterEach(() => window.history.replaceState(null, ''))

  it('reopens the place and the events on the history entry it left', () => {
    const first = page()
    first.memory.track(place)
    first.wrapper.unmount()

    const { memory } = page()

    expect(memory.restored).toMatchObject({ view: 'listWeek', date: '2026-11-23' })
    expect(memory.restored?.events).toEqual([{ title: 'Gig' }])
  })

  it('starts fresh on another history entry', () => {
    const first = page()
    first.memory.track(place)
    first.wrapper.unmount()
    window.history.replaceState(null, '')

    expect(page().memory.restored).toBeUndefined()
  })

  it('starts fresh when what the events depend on has changed', () => {
    const first = page('venue=lido')
    first.memory.track(place)
    first.wrapper.unmount()

    expect(page('venue=berghain').memory.restored).toBeUndefined()
  })
})
