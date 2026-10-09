import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { EventSource } from '@/api/eventSources'
import SourceEditForm from '@/components/SourceEditForm.vue'

function source(overrides: Partial<EventSource> = {}): EventSource {
  return {
    id: 1,
    slug: 'lido',
    name: 'Lido',
    url: 'https://example.org',
    sourceType: 'LIDO',
    enabled: true,
    importIntervalMinutes: 1440,
    maxRetries: 3,
    status: 'SUCCESS',
    lastImportAt: null,
    lastSuccessAt: null,
    lastEventCount: null,
    lastFailureReason: null,
    flaggedAt: null,
    ...overrides,
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

function stubFetch(response: () => Response) {
  const fetchFn = vi.fn<typeof fetch>(async () => response())
  vi.stubGlobal('fetch', fetchFn)
  return fetchFn
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SourceEditForm', () => {
  it('patches the changed fields and emits the stored source', async () => {
    const stored = source({ enabled: false, importIntervalMinutes: 720 })
    const fetchFn = stubFetch(() => json(stored))
    const wrapper = mount(SourceEditForm, { props: { source: source() } })

    await wrapper.get('input[name="enabled"]').setValue(false)
    await wrapper.get('input[name="importIntervalMinutes"]').setValue('720')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const [url, init] = fetchFn.mock.calls[0] ?? []
    expect(`${init?.method} ${String(url)}`).toBe('PATCH /api/admin/event-sources/lido')
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: false, importIntervalMinutes: 720 })
    expect(wrapper.emitted('saved')).toEqual([[stored]])
  })

  it("shows the importer's 400 with its message and emits nothing", async () => {
    stubFetch(() =>
      json(
        {
          status: 400,
          detail: 'Validation failed',
          errors: [{ field: 'maxRetries', message: 'Max retries must not be negative' }],
        },
        400,
      ),
    )
    const wrapper = mount(SourceEditForm, { props: { source: source() } })

    await wrapper.get('input[name="maxRetries"]').setValue('5')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'PATCH /api/admin/event-sources/lido: HTTP 400: Validation failed (Max retries must not be negative)',
    )
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it.each([
    ['importIntervalMinutes', '0', 'The interval must be a whole number of minutes, at least 1.'],
    ['importIntervalMinutes', '', 'The interval must be a whole number of minutes, at least 1.'],
    ['maxRetries', '-1', 'Max retries must be a whole number, at least 0.'],
    ['maxRetries', '1.5', 'Max retries must be a whole number, at least 0.'],
  ])('refuses %s = %j before any request', async (name, value, message) => {
    const fetchFn = stubFetch(() => json(source()))
    const wrapper = mount(SourceEditForm, { props: { source: source() } })

    await wrapper.get(`input[name="${name}"]`).setValue(value)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(message)
    expect(fetchFn).not.toHaveBeenCalled()
  })

  it('keeps Save disabled until a field changes', async () => {
    const wrapper = mount(SourceEditForm, { props: { source: source() } })
    const save = () => wrapper.get('button[type="submit"]').attributes('disabled')

    expect(save()).toBeDefined()
    await wrapper.get('input[name="maxRetries"]').setValue('4')
    expect(save()).toBeUndefined()
  })
})
