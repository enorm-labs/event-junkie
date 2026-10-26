import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  signOut: vi.fn<() => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: AccountEntry } = await import('@/components/AccountEntry.vue')
const { setSession, useSession } = await import('@/composables/useSession')

const Page = defineComponent({ render: () => h('p') })
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.signOut.mockResolvedValue()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(AccountEntry, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

async function openMenu() {
  await wrapper!.find('button[aria-label="Your account"]').trigger('click')
  await flushPromises()
}

describe('AccountEntry', () => {
  it('signed out, is a link to sign-in', async () => {
    setSession(null)
    const view = await mountAt('/en/events')

    expect(view.find('a').attributes('href')).toBe('/en/sign-in')
    expect(view.find('a').attributes('aria-label')).toBe('Sign in')
  })

  it('signed in, opens a menu with the address, Following, My calendar and the account page', async () => {
    setSession(me as never)
    await mountAt('/en/events')
    await openMenu()

    expect(document.body.textContent).toContain('Signed in as ada@example.com')
    const links = [...document.body.querySelectorAll('a')].map((a) => [
      a.textContent?.trim(),
      a.getAttribute('href'),
    ])
    expect(links).toEqual([
      ['Following', '/en/following'],
      ['My calendar', '/en/my-calendar'],
      ['Your account', '/en/account'],
    ])
  })

  it('signs out, clears the session and leaves an account page for home', async () => {
    setSession(me as never)
    await mountAt('/en/following')
    await openMenu()

    const button = [...document.body.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Sign out',
    )!
    button.click()
    await flushPromises()

    expect(account.prepareCsrf).toHaveBeenCalled()
    expect(account.signOut).toHaveBeenCalled()
    expect(useSession().account.value).toBeNull()
    expect(router.currentRoute.value.fullPath).toBe('/en')
  })

  it('stays on any other page after the sign-out', async () => {
    setSession(me as never)
    await mountAt('/en/venues/sisyphos')
    await openMenu()

    ;[...document.body.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === 'Sign out')!
      .click()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/en/venues/sisyphos')
  })

  it('says so when the sign-out fails, and stays signed in', async () => {
    setSession(me as never)
    account.signOut.mockRejectedValue(new Error('offline'))
    await mountAt('/en/events')
    await openMenu()

    ;[...document.body.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === 'Sign out')!
      .click()
    await flushPromises()

    expect(document.body.querySelector('[role=alert]')?.textContent).toContain('did not work')
    expect(useSession().account.value).toEqual(me)
  })
})
