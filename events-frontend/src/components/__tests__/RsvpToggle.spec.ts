import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

import { ApiError } from '@/api/client'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  fetchRsvps: vi.fn<() => Promise<Record<string, string>>>(),
  setRsvp: vi.fn<(slug: string, status: string) => Promise<void>>(),
  withdrawRsvp: vi.fn<(slug: string) => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: RsvpToggle } = await import('@/components/RsvpToggle.vue')
const { setSession } = await import('@/composables/useSession')
const { useRsvps } = await import('@/composables/useRsvps')

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.setRsvp.mockResolvedValue()
  account.withdrawRsvp.mockResolvedValue()
  account.fetchRsvps.mockResolvedValue({})
  account.fetchSession.mockResolvedValue(null)
  useRsvps().statuses.value = {}
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountToggle() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push('/en/events/tonight')
  wrapper = mount(RsvpToggle, { props: { slug: 'tonight' }, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const pressed = (view: VueWrapper, status: string) =>
  view.find(`[data-testid="rsvp-${status}"]`).attributes('aria-pressed')

describe('RsvpToggle', () => {
  it('signed out, both lead to sign-in and back to the event', async () => {
    setSession(null)
    const view = await mountToggle()
    const hrefs = view.findAll('a').map((a) => a.attributes('href'))
    expect(hrefs).toEqual([
      '/en/sign-in?next=/en/events/tonight',
      '/en/sign-in?next=/en/events/tonight',
    ])
  })

  it('sets, changes and withdraws, with the pressed state following', async () => {
    setSession(me as never)
    const view = await mountToggle()

    await view.find('[data-testid="rsvp-interested"]').trigger('click')
    await flushPromises()
    expect(account.setRsvp).toHaveBeenLastCalledWith('tonight', 'interested')
    expect(pressed(view, 'interested')).toBe('true')

    await view.find('[data-testid="rsvp-going"]').trigger('click')
    await flushPromises()
    expect(account.setRsvp).toHaveBeenLastCalledWith('tonight', 'going')
    expect(pressed(view, 'going')).toBe('true')
    expect(pressed(view, 'interested')).toBe('false')

    await view.find('[data-testid="rsvp-going"]').trigger('click')
    await flushPromises()
    expect(account.withdrawRsvp).toHaveBeenCalledWith('tonight')
    expect(pressed(view, 'going')).toBe('false')
  })

  it('goes back and says why when the BFF refuses', async () => {
    setSession(me as never)
    account.setRsvp.mockRejectedValue(new ApiError(503, 'down'))
    const view = await mountToggle()

    await view.find('[data-testid="rsvp-going"]').trigger('click')
    await flushPromises()

    expect(pressed(view, 'going')).toBe('false')
    expect(view.find('[role="status"]').text()).toContain('did not work')
  })
})
