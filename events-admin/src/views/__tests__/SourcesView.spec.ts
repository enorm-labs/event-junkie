import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import type { EventSource } from '@/api/eventSources'
import SourcesView from '@/views/SourcesView.vue'

function source(overrides: Partial<EventSource>): EventSource {
  return {
    id: 1,
    slug: 'x',
    name: 'X',
    url: 'https://example.org',
    sourceType: 'X',
    enabled: true,
    status: 'SUCCESS',
    lastImportAt: null,
    lastSuccessAt: null,
    lastEventCount: null,
    lastFailureReason: null,
    flaggedAt: null,
    ...overrides,
  }
}

vi.mock('@/api/eventSources', () => ({
  fetchAllSources: () =>
    Promise.resolve([
      source({ slug: 'neu', name: 'Neu' }),
      source({ slug: 'amt', name: 'AMT', lastEventCount: 7, lastImportAt: '2026-10-04T11:33:00Z' }),
      source({
        slug: 'aeden',
        name: 'ÆDEN',
        lastEventCount: 12,
        lastImportAt: '2026-10-03T15:38:00Z',
      }),
    ]),
}))

async function mountView() {
  const wrapper = mount(SourcesView)
  await flushPromises()
  return wrapper
}

const slugs = (wrapper: Awaited<ReturnType<typeof mountView>>) =>
  wrapper.findAll('tbody tr').map((row) => row.findAll('td')[1]?.text())

const header = (wrapper: Awaited<ReturnType<typeof mountView>>, label: string) =>
  wrapper.findAll('th button').find((button) => button.text() === label)!

describe('SourcesView', () => {
  it('sorts by name with the German collator, so Æ sits beside A', async () => {
    const wrapper = await mountView()

    expect(slugs(wrapper)).toEqual(['aeden', 'amt', 'neu'])
    expect(wrapper.find('th[aria-sort="ascending"]').text()).toContain('Name')
  })

  it('opens a count column largest first and keeps an empty value last both ways', async () => {
    const wrapper = await mountView()

    await header(wrapper, 'Events').trigger('click')
    expect(slugs(wrapper)).toEqual(['aeden', 'amt', 'neu'])

    await header(wrapper, 'Events').trigger('click')
    expect(slugs(wrapper)).toEqual(['amt', 'aeden', 'neu'])
  })

  it('opens a date column newest first', async () => {
    const wrapper = await mountView()

    await header(wrapper, 'Last import').trigger('click')
    expect(slugs(wrapper)).toEqual(['amt', 'aeden', 'neu'])
  })

  it('filters by name or slug', async () => {
    const wrapper = await mountView()

    await wrapper.get('input').setValue('aed')
    expect(slugs(wrapper)).toEqual(['aeden'])
    expect(wrapper.text()).toContain('1 of 3 sources')
  })

  it('hides Type until the column menu shows it', async () => {
    const wrapper = await mountView()

    expect(wrapper.findAll('th').map((th) => th.text())).not.toContain('Type')
  })
})
