import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

type Page = { entries: unknown[]; next: string | null }
const account = vi.hoisted(() => ({
  fetchSession: vi.fn<() => Promise<unknown>>(),
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchNotificationPage: vi.fn<(options: unknown) => Promise<Page>>(),
  markNotificationRead: vi.fn<(id: string) => Promise<void>>(),
  markAllNotificationsRead: vi.fn<() => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: NotificationsView } = await import('@/views/account/NotificationsView.vue')
const { setSession } = await import('@/composables/useSession')
const { NOTIFICATIONS_READ } = await import('@/lib/notifications')

const Blank = defineComponent({ render: () => h('p') })
const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
const HOUR = 3_600_000

const entry = (
  id: string,
  createdAt: string,
  readAt: string | null = null,
  title = `Night ${id}`,
) => ({
  id,
  kind: 'new_date',
  event: `event-${id}`,
  title,
  date: '2026-11-07',
  aboutKind: 'venue',
  aboutName: 'Club',
  createdAt,
  readAt,
})
const now = () => new Date().toISOString()
const daysAgo = (days: number) => new Date(Date.now() - days * 24 * HOUR).toISOString()

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.markNotificationRead.mockResolvedValue()
  account.markAllNotificationsRead.mockResolvedValue()
  account.fetchNotificationPage.mockResolvedValue({ entries: [], next: null })
  setSession(me as never)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Blank }],
  })
  await router.push(path)
  wrapper = mount(NotificationsView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const button = (view: VueWrapper, text: string) =>
  view.findAll('button').find((b) => b.text() === text)

describe('NotificationsView', () => {
  it('signed out, sends the visitor to sign-in and back', async () => {
    setSession(null)
    await mountAt('/en/notifications?unread=1')

    expect(router.currentRoute.value.path).toBe('/en/sign-in')
    expect(router.currentRoute.value.query.next).toBe('/en/notifications?unread=1')
    expect(account.fetchNotificationPage).not.toHaveBeenCalled()
  })

  it('groups the entries under Today, Yesterday and the date, 50 asked at a time', async () => {
    account.fetchNotificationPage.mockResolvedValue({
      entries: [
        entry('1', now()),
        entry('2', now()),
        entry('3', daysAgo(1)),
        entry('4', '2026-09-01T10:00:00Z'),
      ],
      next: null,
    })
    const view = await mountAt('/en/notifications')

    expect(account.fetchNotificationPage).toHaveBeenCalledWith({ limit: 50, unread: false })
    expect(view.findAll('h2').map((h2) => h2.text())).toEqual([
      'Today',
      'Yesterday',
      'September 1, 2026',
    ])
    expect(view.findAll('section')[0]!.findAll('[data-testid="notification"]')).toHaveLength(2)
    expect(button(view, 'Show more')).toBeUndefined()
  })

  it('Show more asks for the page after the cursor and appends it', async () => {
    account.fetchNotificationPage
      .mockResolvedValueOnce({ entries: [entry('1', now())], next: 'cursor-1' })
      .mockResolvedValueOnce({ entries: [entry('2', now())], next: null })
    const view = await mountAt('/en/notifications')

    await button(view, 'Show more')!.trigger('click')
    await flushPromises()

    expect(account.fetchNotificationPage).toHaveBeenLastCalledWith({
      limit: 50,
      before: 'cursor-1',
      unread: false,
    })
    expect(view.findAll('[data-testid="notification"]')).toHaveLength(2)
    expect(button(view, 'Show more')).toBeUndefined()
  })

  it('keeps the Unread choice in the URL and asks for the unread ones', async () => {
    account.fetchNotificationPage.mockResolvedValue({
      entries: [entry('1', now(), now())],
      next: null,
    })
    const view = await mountAt('/en/notifications')
    account.fetchNotificationPage.mockResolvedValue({ entries: [], next: null })

    await button(view, 'Unread')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.unread).toBe('1')
    expect(account.fetchNotificationPage).toHaveBeenLastCalledWith({ limit: 50, unread: true })
    expect(view.get('[data-testid="notifications-empty"]').text()).toContain('Nothing unread')
    expect(button(view, 'Unread')!.attributes('aria-pressed')).toBe('true')
  })

  it('a reload with ?unread=1 starts on the unread ones', async () => {
    await mountAt('/en/notifications?unread=1')

    expect(account.fetchNotificationPage).toHaveBeenCalledWith({ limit: 50, unread: true })
  })

  it('says so when there is nothing at all, and offers no Mark all as read', async () => {
    const view = await mountAt('/en/notifications')

    expect(view.get('[data-testid="notifications-empty"]').text()).toContain('Nothing new yet')
    expect(button(view, 'Mark all as read')).toBeUndefined()
  })

  it('offers no Mark all as read when everything listed is read', async () => {
    account.fetchNotificationPage.mockResolvedValue({
      entries: [entry('1', now(), now())],
      next: null,
    })
    const view = await mountAt('/en/notifications')

    expect(button(view, 'Mark all as read')).toBeUndefined()
  })

  it('opening an entry marks it read, then tells the bell to count again', async () => {
    account.fetchNotificationPage.mockResolvedValue({ entries: [entry('1', now())], next: null })
    const recount = vi.fn<() => void>()
    window.addEventListener(NOTIFICATIONS_READ, recount)
    const view = await mountAt('/en/notifications')

    expect(view.text()).toContain('Unread')
    await view.get('[data-testid="notification"]').trigger('click')
    await flushPromises()

    expect(account.prepareCsrf).toHaveBeenCalledOnce()
    expect(account.markNotificationRead).toHaveBeenCalledWith('1')
    expect(recount).toHaveBeenCalledOnce()
    window.removeEventListener(NOTIFICATIONS_READ, recount)
  })

  it('marks all as read', async () => {
    account.fetchNotificationPage.mockResolvedValue({
      entries: [entry('1', now()), entry('2', now())],
      next: null,
    })
    const view = await mountAt('/en/notifications')

    await button(view, 'Mark all as read')!.trigger('click')
    await flushPromises()

    expect(account.markAllNotificationsRead).toHaveBeenCalledOnce()
    expect(view.text()).not.toContain('Unread:')
  })

  it('lets a long title wrap instead of cutting it off', async () => {
    const title = 'A very long night '.repeat(12).trim()
    account.fetchNotificationPage.mockResolvedValue({
      entries: [entry('1', now(), null, title)],
      next: null,
    })
    const view = await mountAt('/en/notifications')

    const meta = view.get('[data-testid="notification"]').findAll('span.text-meta')[0]!
    expect(meta.text()).toContain(title)
    expect(meta.classes()).toContain('break-words')
    expect(meta.classes()).not.toContain('truncate')
  })

  it('names the failure when the list does not load', async () => {
    account.fetchNotificationPage.mockRejectedValue(new Error('offline'))
    const view = await mountAt('/en/notifications')

    expect(view.get('[role="alert"]').text()).toContain('your notifications')
  })
})
