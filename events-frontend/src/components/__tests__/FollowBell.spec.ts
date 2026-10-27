import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  setNotify: vi.fn<(kind: string, slug: string, notify: string) => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: FollowBell } = await import('@/components/FollowBell.vue')

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.setNotify.mockResolvedValue()
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

function mountBell(initially: 'off' | 'instant' = 'off') {
  wrapper = mount(FollowBell, {
    props: { kind: 'artist', slug: 'moderat', name: 'Moderat', initially },
  })
  return wrapper
}

describe('FollowBell', () => {
  it('turns the bell on and off, and says which', async () => {
    const view = mountBell()
    const button = view.find('button')
    expect(button.attributes('aria-pressed')).toBe('false')
    expect(button.attributes('aria-label')).toContain('Moderat')

    await button.trigger('click')
    await flushPromises()
    expect(account.setNotify).toHaveBeenLastCalledWith('artist', 'moderat', 'instant')
    expect(button.attributes('aria-pressed')).toBe('true')

    await button.trigger('click')
    await flushPromises()
    expect(account.setNotify).toHaveBeenLastCalledWith('artist', 'moderat', 'off')
    expect(button.attributes('aria-pressed')).toBe('false')
  })

  it('goes back with a message when the BFF refuses', async () => {
    account.setNotify.mockRejectedValue(new Error('offline'))
    const view = mountBell('instant')

    await view.find('button').trigger('click')
    await flushPromises()

    expect(view.find('button').attributes('aria-pressed')).toBe('true')
    expect(view.find('[role="alert"]').exists()).toBe(true)
  })
})
