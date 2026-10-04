import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h, type Ref } from 'vue'

import { useSearchDraft } from '@/composables/useSearchDraft'

let router: Router
let search: Ref<string>
let applySearch: () => void

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:any(.*)*', component: { render: () => null } }],
  })
  await router.push(path)
  const Host = defineComponent({
    setup() {
      ;({ search, applySearch } = useSearchDraft())
      return () => h('div')
    },
  })
  mount(Host, { global: { plugins: [router] } })
}

async function type(value: string) {
  search.value = value
  await flushPromises()
}

async function pause() {
  vi.advanceTimersByTime(250)
  await flushPromises()
}

const query = () => router.currentRoute.value.query

describe('useSearchDraft', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('writes the term once the visitor pauses, keeping the filters and dropping the page', async () => {
    await mountAt('/events?district=kreuzberg&page=3')

    await type('berg')
    expect(query().q).toBeUndefined()
    await pause()

    expect(query()).toEqual({ district: 'kreuzberg', q: 'berg' })
  })

  it('leaves out a term below two characters, as the header search does', async () => {
    await mountAt('/venues?q=astra')

    await type('a')
    await pause()

    expect(query().q).toBeUndefined()
    expect(search.value).toBe('a')
  })

  it('applies at once on Enter, trimmed', async () => {
    await mountAt('/promoters')

    await type('  cocktail ')
    applySearch()
    await flushPromises()

    expect(query().q).toBe('cocktail')
  })

  it('keeps the letters typed after a pause when the URL catches up', async () => {
    await mountAt('/venues')

    await type('ber')
    vi.advanceTimersByTime(250)
    search.value = 'berg'
    await flushPromises()

    expect(query().q).toBe('ber')
    expect(search.value).toBe('berg')
  })

  it('pushes the first term and replaces refinements, so Back leaves the search in one step', async () => {
    await mountAt('/venues')
    await router.push('/venues?sort=name')

    await type('ber')
    await pause()
    await type('berghain')
    await pause()
    expect(query().q).toBe('berghain')

    router.back()
    await flushPromises()

    expect(query()).toEqual({ sort: 'name' })
    expect(search.value).toBe('')
  })

  it('follows the URL when something else changes it', async () => {
    await mountAt('/events?q=jazz')

    await router.push('/events?q=techno')
    await flushPromises()

    expect(search.value).toBe('techno')
  })
})
