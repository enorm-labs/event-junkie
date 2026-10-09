import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { EventSource } from '@/api/eventSources'
import SourceActions from '@/components/SourceActions.vue'

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
    lastImportAt: BEFORE,
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

/**
 * Answers a POST with `actionResponse` and each GET with the next of `reads`; the last read
 * repeats. Each read is a source, or an Error the GET throws as a 502.
 */
function stubFetch(actionResponse: () => Response, reads: (Partial<EventSource> | Error)[]) {
  let read = 0
  const fetchFn = vi.fn<typeof fetch>(async (_input, init) => {
    if (init?.method === 'POST') return actionResponse()
    const next = reads[Math.min(read++, reads.length - 1)]!
    return next instanceof Error ? json({ detail: next.message }, 502) : json(source(next))
  })
  vi.stubGlobal('fetch', fetchFn)
  return fetchFn
}

const calls = (fetchFn: ReturnType<typeof stubFetch>) =>
  fetchFn.mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`)

const BEFORE = '2026-10-09T10:00:00Z'
const AFTER = '2026-10-09T10:05:00Z'
const running = { status: 'RUNNING', lastImportAt: AFTER }
const READ = 'GET /api/admin/event-sources/lido'

/** Fakes only the timers the polling uses, so `flushPromises` keeps its real `setImmediate`. */
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
})

// A menu's portal outlives its row unless the row unmounts, and a later test would find its items.
enableAutoUnmount(afterEach)

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const MENU = 'button[aria-label="More actions for Lido"]'

/** Opens the row menu, which reka-ui renders in a portal on `document.body`. */
async function openMenu(wrapper: ReturnType<typeof mount>) {
  await wrapper.get(MENU).trigger('keydown', { key: 'Enter' })
  await flushPromises()
}

const menuItem = (label: string) =>
  document.body.querySelector<HTMLElement>(`[role="menuitem"][aria-label="${label} Lido"]`)

/** Import is a button in the row; Force import, Retry and Edit are items of the row menu. */
async function click(wrapper: ReturnType<typeof mount>, label: string) {
  if (label === 'Import') {
    await wrapper.get(`button[aria-label="Import Lido"]`).trigger('click')
  } else {
    await openMenu(wrapper)
    menuItem(label)!.click()
  }
  await flushPromises()
}

const mountActions = (props: { source: EventSource }) =>
  mount(SourceActions, { props, attachTo: document.body })

/** Runs the next poll and lets its read settle. */
async function tick() {
  await vi.advanceTimersByTimeAsync(3_000)
  await flushPromises()
}

describe('SourceActions', () => {
  it.each([
    ['Import', '/api/admin/event-sources/lido/import'],
    ['Force import', '/api/admin/event-sources/lido/import?force=true'],
  ])('%s posts the import and reads the source again', async (label, path) => {
    const fetchFn = stubFetch(() => json({ message: 'started' }, 202), [running])
    const wrapper = mountActions({ source: source() })

    await click(wrapper, label)

    expect(calls(fetchFn)).toEqual([`POST ${path}`, READ])
    expect(wrapper.emitted('updated')).toEqual([[source(running)]])
    wrapper.unmount()
  })

  it('polls every 3 s until the import ends with SUCCESS, then enables the buttons', async () => {
    const done = { status: 'SUCCESS', lastImportAt: AFTER, lastEventCount: 12 }
    // The first read still shows the previous run: SUCCESS with the old lastImportAt.
    const fetchFn = stubFetch(() => json({}, 202), [{}, running, done])
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Import')
    expect(wrapper.get('[role="status"]').text()).toBe('Waiting for the import to end…')
    expect(wrapper.get('button[aria-label="Import Lido"]').attributes('disabled')).toBeDefined()

    await tick()
    await tick()

    expect(calls(fetchFn)).toEqual(['POST /api/admin/event-sources/lido/import', READ, READ, READ])
    expect(wrapper.emitted('updated')?.slice(-1)[0]).toEqual([source(done)])
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.get('button[aria-label="Import Lido"]').attributes('disabled')).toBeUndefined()

    await tick()
    expect(fetchFn).toHaveBeenCalledTimes(4)
  })

  it('stops polling when the import ends with FAILED', async () => {
    const failed = { status: 'FAILED', lastImportAt: AFTER, lastFailureReason: 'HTTP_5XX' }
    const fetchFn = stubFetch(() => json({}, 202), [running, failed])
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Force import')
    await tick()
    await tick()

    expect(fetchFn).toHaveBeenCalledTimes(3)
    expect(wrapper.emitted('updated')?.slice(-1)[0]).toEqual([source(failed)])
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('stops after 10 minutes and says the import still runs', async () => {
    const fetchFn = stubFetch(() => json({}, 202), [running])
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Import')
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toBe(
      'Still running after 10 minutes. Reload the page later.',
    )
    // The POST, then a read at 0 s, 3 s, … 600 s.
    expect(fetchFn).toHaveBeenCalledTimes(1 + 201)
    await tick()
    expect(fetchFn).toHaveBeenCalledTimes(1 + 201)
    expect(wrapper.get('button[aria-label="Import Lido"]').attributes('disabled')).toBeUndefined()
  })

  it('stops polling when the row unmounts', async () => {
    const fetchFn = stubFetch(() => json({}, 202), [running])
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Import')
    await tick()
    wrapper.unmount()
    await tick()
    await tick()

    expect(fetchFn).toHaveBeenCalledTimes(3)
  })

  it('stops polling on a read error and shows it', async () => {
    const fetchFn = stubFetch(() => json({}, 202), [running, new Error('Bad Gateway')])
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Import')
    await tick()
    await tick()

    expect(wrapper.get('[role="alert"]').text()).toBe(`${READ}: HTTP 502: Bad Gateway`)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(fetchFn).toHaveBeenCalledTimes(3)
  })

  it('Retry shows the reset source, then polls until the scheduler ran it', async () => {
    const done = { status: 'SUCCESS', lastImportAt: AFTER }
    const fetchFn = stubFetch(() => json(source({ status: 'IDLE' })), [{ status: 'IDLE' }, done])
    const wrapper = mountActions({ source: source({ status: 'FAILED' }) })

    await click(wrapper, 'Retry')
    await tick()

    expect(calls(fetchFn)).toEqual(['POST /api/admin/event-sources/lido/retry', READ, READ])
    expect(wrapper.emitted('updated')).toEqual([
      [source({ status: 'IDLE' })],
      [source({ status: 'IDLE' })],
      [source(done)],
    ])
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it.each([
    ['FAILED', true],
    ['RUNNING', true],
    ['SUCCESS', false],
    ['IDLE', false],
  ])('the row menu offers Retry for a %s source: %s', async (status, shown) => {
    const wrapper = mountActions({ source: source({ status }) })

    await openMenu(wrapper)

    expect(menuItem('Force import')).not.toBeNull()
    expect(menuItem('Retry') !== null).toBe(shown)
    wrapper.unmount()
  })

  it('keeps Edit in the menu open while the row polls, and Force import and Retry not', async () => {
    stubFetch(() => json({}, 202), [running])
    const wrapper = mountActions({ source: source({ status: 'FAILED' }) })

    await click(wrapper, 'Import')
    await openMenu(wrapper)

    expect(menuItem('Edit')!.hasAttribute('data-disabled')).toBe(false)
    expect(menuItem('Force import')!.hasAttribute('data-disabled')).toBe(true)
    expect(menuItem('Retry')!.hasAttribute('data-disabled')).toBe(true)
  })

  it('keeps Import in the row and the other actions in the menu', () => {
    const wrapper = mountActions({ source: source({ status: 'FAILED' }) })

    expect(wrapper.findAll('button').map((b) => b.attributes('aria-label'))).toEqual([
      'Import Lido',
      'More actions for Lido',
    ])
    expect(menuItem('Force import')).toBeNull()
    wrapper.unmount()
  })

  it('shows an HTTP error, emits nothing and enables the buttons again', async () => {
    const fetchFn = stubFetch(
      () => json({ status: 404, detail: "Event source not found: 'lido'" }, 404),
      [{}],
    )
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Import')

    expect(wrapper.get('[role="alert"]').text()).toBe(
      "POST /api/admin/event-sources/lido/import: HTTP 404: Event source not found: 'lido'",
    )
    expect(calls(fetchFn)).toHaveLength(1)
    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(wrapper.get('button[aria-label="Import Lido"]').attributes('disabled')).toBeUndefined()
  })

  it('Edit opens the form, and a save emits the PATCH answer without another read', async () => {
    const stored = source({ maxRetries: 5 })
    const fetchFn = vi.fn<typeof fetch>(async () => json(stored))
    vi.stubGlobal('fetch', fetchFn)
    const wrapper = mountActions({ source: source() })

    await click(wrapper, 'Edit')
    const retries = document.querySelector<HTMLInputElement>('input[name="maxRetries"]')
    expect(retries).not.toBeNull()
    retries!.value = '5'
    retries!.dispatchEvent(new Event('input'))
    document.querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(calls(fetchFn)).toEqual(['PATCH /api/admin/event-sources/lido'])
    expect(wrapper.emitted('updated')).toEqual([[stored]])
    wrapper.unmount()
  })

  it('disables every button while an action runs', async () => {
    let answer: (response: Response) => void = () => {}
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() => new Promise((resolve) => (answer = resolve))),
    )
    const wrapper = mountActions({ source: source({ status: 'FAILED' }) })

    await wrapper.get('button[aria-label="Import Lido"]').trigger('click')

    expect(wrapper.findAll('button').every((b) => b.attributes('disabled') !== undefined)).toBe(
      true,
    )
    answer(json({}, 202))
    wrapper.unmount()
  })
})
