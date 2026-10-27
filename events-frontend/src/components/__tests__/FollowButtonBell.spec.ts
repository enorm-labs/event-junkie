import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h, ref } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchFollowNotify: vi.fn<(kind: string, slug: string) => Promise<string | null>>(),
  follow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  unfollow: vi.fn<(kind: string, slug: string) => Promise<void>>(),
  setNotify: vi.fn<(kind: string, slug: string, notify: string) => Promise<void>>(),
}))
const push = vi.hoisted(() => ({ state: { value: 'loading' }, load: vi.fn<() => Promise<void>>() }))

vi.mock('@/api/account', () => account)
vi.mock('@/composables/usePush', () => ({
  usePush: () => ({ state: push.state, load: push.load }),
}))

const { default: FollowButton } = await import('@/components/FollowButton.vue')
const { setSession } = await import('@/composables/useSession')

const Page = defineComponent({ render: () => h('p') })
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  // A ref, so the button re-renders once the state arrives, as with the real composable.
  push.state = ref('loading')
  account.prepareCsrf.mockResolvedValue()
  account.follow.mockResolvedValue()
  account.unfollow.mockResolvedValue()
  account.setNotify.mockResolvedValue()
  setSession(me as never)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

/** Push is in `state` once loaded: `off` means the site sends push but this browser has no subscription. */
async function mountButton(state: string, props: Record<string, unknown> = { name: 'Sisyphos' }) {
  push.load.mockImplementation(async () => {
    push.state.value = state
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push('/en/venues/sisyphos')
  wrapper = mount(FollowButton, {
    props: { kind: 'venue', slug: 'sisyphos', ...props },
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

const bell = (view: VueWrapper) => view.find('button[aria-pressed]')

describe('FollowButton with the bell', () => {
  it('shows the stored bell of a follow, and hides it with the unfollow', async () => {
    account.fetchFollowNotify.mockResolvedValue('instant')
    const view = await mountButton('on')

    expect(bell(view).attributes('aria-pressed')).toBe('true')
    expect(bell(view).attributes('aria-label')).toContain('Sisyphos')

    await view.findAll('button')[0]!.trigger('click')
    await flushPromises()
    expect(account.unfollow).toHaveBeenCalledWith('venue', 'sisyphos')
    expect(bell(view).exists()).toBe(false)
  })

  it('shows no bell before a follow, and an off one after it', async () => {
    account.fetchFollowNotify.mockResolvedValue(null)
    const view = await mountButton('on')

    expect(bell(view).exists()).toBe(false)
    expect(push.load).not.toHaveBeenCalled()

    await view.find('button').trigger('click')
    await flushPromises()
    expect(bell(view).attributes('aria-pressed')).toBe('false')
  })

  it('hints at the account page when the bell goes on in a browser without a subscription', async () => {
    account.fetchFollowNotify.mockResolvedValue('off')
    const view = await mountButton('off')

    await bell(view).trigger('click')
    await flushPromises()

    expect(account.setNotify).toHaveBeenCalledWith('venue', 'sisyphos', 'instant')
    expect(view.find('a[href="/en/account"]').exists()).toBe(true)

    await bell(view).trigger('click')
    await flushPromises()
    expect(view.find('a[href="/en/account"]').exists()).toBe(false)
  })

  it('gives no hint when this browser has a subscription', async () => {
    account.fetchFollowNotify.mockResolvedValue('off')
    const view = await mountButton('on')

    await bell(view).trigger('click')
    await flushPromises()

    expect(account.setNotify).toHaveBeenCalledWith('venue', 'sisyphos', 'instant')
    expect(view.find('a[href="/en/account"]').exists()).toBe(false)
  })

  it('shows no bell where the site sends no push, or without a name', async () => {
    account.fetchFollowNotify.mockResolvedValue('off')
    expect(bell(await mountButton('unavailable')).exists()).toBe(false)
    wrapper?.unmount()
    push.state.value = 'on'
    expect(bell(await mountButton('on', {})).exists()).toBe(false)
  })
})
