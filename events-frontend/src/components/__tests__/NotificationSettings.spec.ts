import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchPushKey: vi.fn<() => Promise<string | null>>(),
  savePushSubscription: vi.fn<(subscription: unknown) => Promise<void>>(),
  forgetPushSubscription: vi.fn<(endpoint: string) => Promise<void>>(),
  fetchFollows: vi.fn<() => Promise<{ notify?: string }[]>>(),
  setAllNotify: vi.fn<(notify: string) => Promise<number>>(),
  setChangePush: vi.fn<(enabled: boolean) => Promise<void>>(),
  setReminderPush: vi.fn<(enabled: boolean) => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: NotificationSettings } = await import('@/components/NotificationSettings.vue')

const ENDPOINT = 'https://fcm.googleapis.com/fcm/send/abc'
const subscriptionJson = { endpoint: ENDPOINT, keys: { p256dh: 'p', auth: 'a' } }

/** A browser with Web Push, whose one registration and subscription the test can inspect. */
function browser(options: { permission?: NotificationPermission; subscribed?: boolean } = {}) {
  const subscription = {
    endpoint: ENDPOINT,
    toJSON: () => subscriptionJson,
    unsubscribe: vi.fn<() => Promise<boolean>>(async () => true),
  }
  type Subscription = typeof subscription
  let current: Subscription | null = options.subscribed ? subscription : null
  const registration = {
    active: {},
    installing: null,
    waiting: null,
    unregister: vi.fn<() => Promise<boolean>>(async () => true),
    pushManager: {
      getSubscription: vi.fn<() => Promise<Subscription | null>>(async () => current),
      subscribe: vi.fn<(options: PushSubscriptionOptionsInit) => Promise<Subscription>>(
        async () => {
          current = subscription
          return subscription
        },
      ),
    },
  }
  let registered = !!options.subscribed
  const serviceWorker = {
    register: vi.fn<(url: string, options: RegistrationOptions) => Promise<typeof registration>>(
      async () => {
        registered = true
        return registration
      },
    ),
    getRegistration: vi.fn<(scope: string) => Promise<typeof registration | undefined>>(async () =>
      registered ? registration : undefined,
    ),
  }
  const notification = {
    permission: options.permission ?? 'default',
    requestPermission: vi.fn<() => Promise<NotificationPermission>>(async () => 'granted'),
  }
  Object.defineProperty(navigator, 'serviceWorker', { value: serviceWorker, configurable: true })
  vi.stubGlobal('PushManager', function PushManager() {})
  vi.stubGlobal('Notification', notification)
  return { serviceWorker, registration, subscription, notification }
}

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.fetchPushKey.mockResolvedValue('BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIg')
  account.savePushSubscription.mockResolvedValue()
  account.forgetPushSubscription.mockResolvedValue()
  account.fetchFollows.mockResolvedValue([])
  account.setAllNotify.mockResolvedValue(0)
  account.setChangePush.mockResolvedValue()
  account.setReminderPush.mockResolvedValue()
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  Reflect.deleteProperty(navigator, 'serviceWorker')
  vi.restoreAllMocks()
})

async function mountSettings(changePush = true, reminderPush = true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push('/en/account')
  wrapper = mount(NotificationSettings, {
    props: { changePush, reminderPush },
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

describe('NotificationSettings', () => {
  it('says so when the site sends no push', async () => {
    browser()
    account.fetchPushKey.mockResolvedValue(null)
    const view = await mountSettings()
    expect(view.text()).toContain('not available')
    // The bells sit on the Following page's tab of what the account follows (#4080).
    expect(view.find('a[href="/en/following?tab=follows"]').exists()).toBe(true)
    expect(view.find('[data-testid="push-toggle"]').exists()).toBe(false)
  })

  it('on an iPhone in the browser, explains the Home Screen', async () => {
    browser()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_4 like Mac OS X)',
    )
    const view = await mountSettings()
    expect(view.text()).toContain('Add to Home Screen')
  })

  it('turns on: asks, registers the worker under /push/, subscribes and hands the subscription over', async () => {
    const { serviceWorker, registration } = browser()
    const view = await mountSettings()
    expect(view.text()).toContain('off in this browser')

    await view.find('[data-testid="push-toggle"]').trigger('click')
    await flushPromises()

    expect(serviceWorker.register).toHaveBeenCalledWith('/push-sw.js', { scope: '/push/' })
    expect(registration.pushManager.subscribe).toHaveBeenCalledWith(
      expect.objectContaining({ userVisibleOnly: true }),
    )
    expect(account.savePushSubscription).toHaveBeenCalledWith(subscriptionJson)
    expect(view.text()).toContain('on in this browser')
  })

  it('right after turning on, offers to ring every silent bell, and rings them on yes', async () => {
    browser()
    account.fetchFollows.mockResolvedValue([
      { notify: 'off' },
      { notify: 'off' },
      { notify: 'instant' },
    ])
    account.setAllNotify.mockResolvedValue(3)
    const view = await mountSettings()

    await view.find('[data-testid="push-toggle"]').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('The bell is off on 2 you follow')

    await view.find('[data-testid="ring-all"]').trigger('click')
    await flushPromises()
    expect(account.setAllNotify).toHaveBeenCalledWith('instant')
    expect(view.text()).toContain('The bell is on for all 3')
    expect(view.find('[data-testid="ring-all"]').exists()).toBe(false)
  })

  it('makes no offer when every bell is on already, and none on "Not now"', async () => {
    browser()
    account.fetchFollows.mockResolvedValue([{ notify: 'instant' }])
    const view = await mountSettings()
    await view.find('[data-testid="push-toggle"]').trigger('click')
    await flushPromises()
    expect(view.find('[data-testid="ring-all"]').exists()).toBe(false)
    expect(account.setAllNotify).not.toHaveBeenCalled()
  })

  it('turns off: forgets the subscription and unregisters the worker', async () => {
    const { registration, subscription } = browser({ permission: 'granted', subscribed: true })
    const view = await mountSettings()
    expect(view.text()).toContain('on in this browser')

    await view.find('[data-testid="push-toggle"]').trigger('click')
    await flushPromises()

    expect(account.forgetPushSubscription).toHaveBeenCalledWith(ENDPOINT)
    expect(subscription.unsubscribe).toHaveBeenCalled()
    expect(registration.unregister).toHaveBeenCalled()
    expect(view.text()).toContain('off in this browser')
  })

  it('blocked in the browser, it says where to allow them', async () => {
    browser({ permission: 'denied' })
    const view = await mountSettings()
    expect(view.text()).toContain('blocked')
  })

  it('undoes the subscription when the BFF does not store it', async () => {
    const { registration, subscription } = browser()
    account.savePushSubscription.mockRejectedValue(new Error('offline'))
    const view = await mountSettings()

    await view.find('[data-testid="push-toggle"]').trigger('click')
    await flushPromises()

    expect(subscription.unsubscribe).toHaveBeenCalled()
    expect(registration.unregister).toHaveBeenCalled()
    expect(view.find('[role="alert"]').text()).toContain('did not work')
    expect(view.text()).toContain('off in this browser')
  })

  it('"Changes to my plans" shows the account\'s setting and saves a change', async () => {
    browser()
    const view = await mountSettings(false)
    const box = view.get<HTMLInputElement>('[data-testid="change-push"]')
    expect(box.element.checked).toBe(false)

    await box.setValue(true)
    await flushPromises()

    expect(account.setChangePush).toHaveBeenCalledWith(true)
    expect(box.element.checked).toBe(true)
  })

  it('"Changes to my plans" goes back and says so when the change is not saved', async () => {
    browser()
    account.setChangePush.mockRejectedValue(new Error('offline'))
    const view = await mountSettings()
    const box = view.get<HTMLInputElement>('[data-testid="change-push"]')

    await box.setValue(false)
    await flushPromises()

    expect(box.element.checked).toBe(true)
    expect(view.find('[role="alert"]').text()).toContain('did not work')
  })

  it('"Reminders on the day" shows the account\'s setting and saves a change', async () => {
    browser()
    const view = await mountSettings(true, false)
    const box = view.get<HTMLInputElement>('[data-testid="reminder-push"]')
    expect(box.element.checked).toBe(false)

    await box.setValue(true)
    await flushPromises()

    expect(account.setReminderPush).toHaveBeenCalledWith(true)
    expect(account.setChangePush).not.toHaveBeenCalled()
    expect(box.element.checked).toBe(true)
  })

  it('"Reminders on the day" goes back and says so when the change is not saved', async () => {
    browser()
    account.setReminderPush.mockRejectedValue(new Error('offline'))
    const view = await mountSettings()
    const box = view.get<HTMLInputElement>('[data-testid="reminder-push"]')

    await box.setValue(false)
    await flushPromises()

    expect(box.element.checked).toBe(true)
    expect(view.find('[role="alert"]').text()).toContain('did not work')
  })
})
