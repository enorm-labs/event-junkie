import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchUnreadCount: vi.fn<() => Promise<number>>(),
  fetchNotifications: vi.fn<() => Promise<unknown[]>>(),
  markNotificationRead: vi.fn<(id: string) => Promise<void>>(),
  markAllNotificationsRead: vi.fn<() => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: NotificationBell } = await import('@/components/NotificationBell.vue')

const Page = defineComponent({ render: () => h('p') })

const entry = (id: string, readAt: string | null = null) => ({
  id,
  kind: 'new_date',
  event: `event-${id}`,
  title: `Night ${id}`,
  date: '2026-11-07',
  aboutKind: 'venue',
  aboutName: 'Club',
  createdAt: '2026-10-28T10:00:00Z',
  readAt,
})

let wrapper: VueWrapper | undefined
let router: Router

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.markNotificationRead.mockResolvedValue()
  account.markAllNotificationsRead.mockResolvedValue()
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
  wrapper = mount(NotificationBell, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

const bell = () => wrapper!.get('[data-testid="notification-bell"]')

async function openMenu() {
  await bell().trigger('click')
  await flushPromises()
}

describe('NotificationBell', () => {
  it('shows the unread count, capped at 9+, and names it for a screen reader', async () => {
    account.fetchUnreadCount.mockResolvedValue(12)
    await mountAt('/en/events')

    expect(wrapper!.get('[data-testid="notification-badge"]').text()).toBe('9+')
    expect(bell().attributes('aria-label')).toBe('Notifications, 12 unread')
  })

  it('shows no badge when nothing is unread', async () => {
    account.fetchUnreadCount.mockResolvedValue(0)
    await mountAt('/en/events')

    expect(wrapper!.find('[data-testid="notification-badge"]').exists()).toBe(false)
    expect(bell().attributes('aria-label')).toBe('Notifications')
  })

  it('asks again on a route change and when the tab comes back into view', async () => {
    account.fetchUnreadCount.mockResolvedValue(1)
    await mountAt('/en/events')
    await router.push('/en/venues')
    await flushPromises()
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()

    expect(account.fetchUnreadCount).toHaveBeenCalledTimes(3)
  })

  it('lists the entries, unread ones marked, each a link to its event', async () => {
    account.fetchUnreadCount.mockResolvedValue(1)
    account.fetchNotifications.mockResolvedValue([entry('1'), entry('2', '2026-10-28T11:00:00Z')])
    await mountAt('/en/events')
    await openMenu()

    const links = [...document.body.querySelectorAll('[data-testid="notification"]')]
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/en/events/event-1',
      '/en/events/event-2',
    ])
    expect(links[0]!.textContent).toContain('Unread')
    expect(links[0]!.textContent).toContain('New date at Club')
    expect(links[1]!.textContent).not.toContain('Unread')
  })

  it('opening an unread entry marks it read, and the count asked after the mark is lower', async () => {
    account.fetchUnreadCount.mockResolvedValueOnce(1).mockResolvedValue(0)
    account.fetchNotifications.mockResolvedValue([entry('1')])
    await mountAt('/en/events')
    await openMenu()

    document.body.querySelector<HTMLElement>('[data-testid="notification"]')!.click()
    await flushPromises()

    expect(account.prepareCsrf).toHaveBeenCalledOnce()
    expect(account.markNotificationRead).toHaveBeenCalledWith('1')
    expect(account.markNotificationRead.mock.invocationCallOrder[0]).toBeLessThan(
      account.fetchUnreadCount.mock.invocationCallOrder[1]!,
    )
    expect(wrapper!.find('[data-testid="notification-badge"]').exists()).toBe(false)
  })

  it('marks all as read', async () => {
    account.fetchUnreadCount.mockResolvedValue(2)
    account.fetchNotifications.mockResolvedValue([entry('1'), entry('2')])
    await mountAt('/en/events')
    await openMenu()

    const button = [...document.body.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Mark all as read',
    )
    button!.click()
    await flushPromises()

    expect(account.markAllNotificationsRead).toHaveBeenCalledOnce()
    expect(wrapper!.find('[data-testid="notification-badge"]').exists()).toBe(false)
    expect(document.body.textContent).not.toContain('Unread')
  })

  it('says so when there is nothing yet', async () => {
    account.fetchUnreadCount.mockResolvedValue(0)
    account.fetchNotifications.mockResolvedValue([])
    await mountAt('/en/events')
    await openMenu()

    expect(document.body.textContent).toContain('Nothing new yet')
  })
})
