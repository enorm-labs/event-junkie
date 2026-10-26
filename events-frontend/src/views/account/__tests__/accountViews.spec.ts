import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

import { ApiError } from '@/api/client'
import { setI18nLocale } from '@/i18n'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  fetchSessionState: vi.fn<() => Promise<{ account: unknown; signUpOpen: boolean }>>(),
  requestLink: vi.fn<(email: string, locale: string, next?: string) => Promise<void>>(),
  signIn: vi.fn<(token: string) => Promise<unknown>>(),
  fetchAccount: vi.fn<() => Promise<unknown>>(),
  signOut: vi.fn<() => Promise<void>>(),
  signOutEverywhere: vi.fn<() => Promise<void>>(),
  deleteAccount: vi.fn<() => Promise<void>>(),
}))

vi.mock('@/api/account', () => ({ ...account, EXPORT_URL: '/api/me/export' }))

const { default: SignInView } = await import('@/views/account/SignInView.vue')
const { default: SignInConfirmView } = await import('@/views/account/SignInConfirmView.vue')
const { default: AccountView } = await import('@/views/account/AccountView.vue')

const Page = defineComponent({ render: () => h('p') })
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.fetchSession.mockResolvedValue(null)
  account.fetchSessionState.mockResolvedValue({ account: null, signUpOpen: true })
})

afterEach(() => {
  setI18nLocale('en')
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(view: object, path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(view, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('SignInView', () => {
  it('asks for the CSRF cookie, sends the link in the page language and goes to check-mail', async () => {
    account.requestLink.mockResolvedValue()
    // The app's router sets the page language before the view renders.
    setI18nLocale('de')
    const view = await mountAt(SignInView, '/de/sign-in')
    expect(account.prepareCsrf).toHaveBeenCalled()

    await view.find('input[type="email"]').setValue('  ada@example.com ')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(account.requestLink).toHaveBeenCalledWith('ada@example.com', 'de', undefined)
    expect(router.currentRoute.value.fullPath).toBe('/de/sign-in/check-mail')
  })

  it.each([
    [400, 'This is not an e-mail address'],
    [429, 'Too many links were requested from here'],
    [503, 'The mail could not be sent'],
  ])('says what a %i means, in an alert', async (status, text) => {
    account.requestLink.mockRejectedValue(new ApiError(status, 'no'))
    const view = await mountAt(SignInView, '/en/sign-in')
    await view.find('input[type="email"]').setValue('ada@example.com')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(view.find('[role="alert"]').text()).toContain(text)
    expect(view.find('input').attributes('aria-invalid')).toBe('true')
    expect(router.currentRoute.value.fullPath).toBe('/en/sign-in')
  })

  it('says an empty field is no address, without a request', async () => {
    const view = await mountAt(SignInView, '/en/sign-in')
    await view.find('form').trigger('submit')
    await flushPromises()

    expect(account.requestLink).not.toHaveBeenCalled()
    expect(view.find('[role="alert"]').text()).toContain('This is not an e-mail address')
  })

  it('sends a signed-in visitor to the Following page', async () => {
    account.fetchSessionState.mockResolvedValue({ account: me, signUpOpen: true })
    await mountAt(SignInView, '/en/sign-in')
    expect(router.currentRoute.value.fullPath).toBe('/en/following')
  })

  it('says sign-up is paused, and still offers the form to existing accounts', async () => {
    const open = await mountAt(SignInView, '/en/sign-in')
    expect(open.find('[role="status"]').exists()).toBe(false)
    open.unmount()

    account.fetchSessionState.mockResolvedValue({ account: null, signUpOpen: false })
    const paused = await mountAt(SignInView, '/en/sign-in')
    expect(paused.find('[role="status"]').text()).toContain('New accounts are paused')
    expect(paused.find('form button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})

describe('SignInView with a page to return to', () => {
  it('passes `next` on to the link request', async () => {
    account.requestLink.mockResolvedValue()
    const view = await mountAt(SignInView, '/en/sign-in?next=/en/artists/ada')
    await view.find('input').setValue('ada@example.com')
    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.requestLink).toHaveBeenCalledWith('ada@example.com', 'en', '/en/artists/ada')
  })
})

describe('SignInConfirmView', () => {
  it('takes the token out of the address bar and signs in only on the click', async () => {
    account.signIn.mockResolvedValue(me)
    const replaceState = vi.spyOn(history, 'replaceState')
    const view = await mountAt(SignInConfirmView, '/en/sign-in/confirm#token=abc_-123')

    expect(replaceState).toHaveBeenCalledWith(history.state, '', '/en/sign-in/confirm')
    expect(account.signIn).not.toHaveBeenCalled()

    await view.find('button').trigger('click')
    await flushPromises()
    expect(account.signIn).toHaveBeenCalledWith('abc_-123')
    expect(router.currentRoute.value.fullPath).toBe('/en/following')
  })

  it('returns to the page in `next` after the sign-in, and ignores one that is not a page of this site', async () => {
    account.signIn.mockResolvedValue(me)
    const view = await mountAt(
      SignInConfirmView,
      '/en/sign-in/confirm?next=/en/venues/sisyphos#token=abc',
    )
    await view.find('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/en/venues/sisyphos')

    wrapper?.unmount()
    const other = await mountAt(
      SignInConfirmView,
      '/en/sign-in/confirm?next=//evil.example#token=abc',
    )
    await other.find('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/en/following')
  })

  it('offers a new link when the token was used or expired', async () => {
    account.signIn.mockRejectedValue(new ApiError(401, 'no'))
    const view = await mountAt(SignInConfirmView, '/en/sign-in/confirm#token=abc')
    await view.find('button').trigger('click')
    await flushPromises()

    expect(view.find('[role="alert"]').text()).toContain('already used or has expired')
    expect(view.find('button').exists()).toBe(false)
    expect(view.find('a[href="/en/sign-in"]').exists()).toBe(true)
  })

  it('keeps the button after a failure a retry can fix', async () => {
    account.signIn.mockRejectedValue(new ApiError(503, 'no'))
    const view = await mountAt(SignInConfirmView, '/en/sign-in/confirm#token=abc')
    await view.find('button').trigger('click')
    await flushPromises()

    expect(view.find('[role="alert"]').text()).not.toBe('')
    expect(view.find('button').exists()).toBe(true)
  })

  it('says the link is incomplete when it carries no token', async () => {
    const view = await mountAt(SignInConfirmView, '/en/sign-in/confirm')
    expect(view.find('[role="alert"]').text()).toContain('incomplete')
    expect(view.find('button').exists()).toBe(false)
  })
})

describe('AccountView', () => {
  it('shows the address, the date and an export link', async () => {
    account.fetchAccount.mockResolvedValue(me)
    const view = await mountAt(AccountView, '/en/account')

    expect(view.text()).toContain('Signed in as ada@example.com')
    expect(view.text()).toMatch(/Account since \S.*2026/)
    expect(view.find('a[href="/api/me/export"]').attributes('download')).toBeDefined()
  })

  it('sends a browser that is not signed in to the sign-in page', async () => {
    account.fetchAccount.mockRejectedValue(new ApiError(401, 'no'))
    await mountAt(AccountView, '/en/account')
    expect(router.currentRoute.value.fullPath).toBe('/en/sign-in')
  })

  it('signs out and says so', async () => {
    account.fetchAccount.mockResolvedValue(me)
    account.signOut.mockResolvedValue()
    const view = await mountAt(AccountView, '/en/account')
    const button = view.findAll('button').find((b) => b.text() === 'Sign out')
    await button?.trigger('click')
    await flushPromises()

    expect(account.signOut).toHaveBeenCalled()
    expect(view.find('[role="status"]').text()).toContain('You are signed out.')
  })

  it('deletes only after the confirmation', async () => {
    account.fetchAccount.mockResolvedValue(me)
    account.deleteAccount.mockResolvedValue()
    const view = await mountAt(AccountView, '/en/account')
    await view
      .findAll('button')
      .find((b) => b.text() === 'Delete account')
      ?.trigger('click')
    await flushPromises()
    expect(account.deleteAccount).not.toHaveBeenCalled()

    const confirm = [...document.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Delete for good',
    )
    confirm?.click()
    await flushPromises()

    expect(account.deleteAccount).toHaveBeenCalled()
    expect(view.find('[role="status"]').text()).toContain('Your account is deleted.')
  })
})
