import { readonly, ref } from 'vue'
import {
  fetchPushKey,
  forgetPushSubscription,
  prepareCsrf,
  savePushSubscription,
} from '@/api/account'

/**
 * Web Push for this browser (#3894, ADR-055). The service worker is registered only by {@link turnOn}, and
 * {@link turnOff} unregisters it again, so a visitor who never turns notifications on stores nothing.
 *
 * - `unavailable`: this site sends no push right now (no VAPID keys on the cluster).
 * - `homeScreen`: iPhone or iPad in the browser; push works only after "Add to Home Screen".
 * - `unsupported`: the browser has no Web Push.
 * - `denied`: the visitor blocked notifications for this site in the browser.
 */
export type PushState =
  | 'loading'
  | 'unsupported'
  | 'homeScreen'
  | 'unavailable'
  | 'denied'
  | 'off'
  | 'on'

const WORKER = '/push-sw.js'
/** Below every page, so the worker controls none and cannot touch a page load (ADR-055 rule 2). */
const SCOPE = '/push/'

// Module state: the account page and the Following page read the same answer.
const state = ref<PushState>('loading')
const busy = ref(false)
let publicKey: string | null = null

function isAppleMobile(): boolean {
  return (
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  )
}

function isStandalone(): boolean {
  return (
    window.matchMedia?.('(display-mode: standalone)').matches === true ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  )
}

function supported(): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** The VAPID key arrives base64url; `subscribe` takes the raw bytes. */
function keyBytes(key: string): Uint8Array<ArrayBuffer> {
  const base64 = (key + '='.repeat((4 - (key.length % 4)) % 4))
    .replace(/-/g, '+')
    .replace(/_/g, '/')
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
}

/** `pushManager.subscribe` needs an active worker, and `serviceWorker.ready` waits for one that controls this page, which ours never does. */
function activated(registration: ServiceWorkerRegistration): Promise<void> {
  const worker = registration.installing ?? registration.waiting
  if (registration.active || !worker) return Promise.resolve()
  return new Promise((resolve) => {
    worker.addEventListener('statechange', () => {
      if (worker.state === 'activated') resolve()
    })
  })
}

async function subscription(): Promise<PushSubscription | null> {
  const registration = await navigator.serviceWorker.getRegistration(SCOPE)
  return (await registration?.pushManager.getSubscription()) ?? null
}

/** The key first: without it the site sends no push, whatever this browser could do. */
async function load(): Promise<void> {
  publicKey = await fetchPushKey()
  if (!publicKey) state.value = 'unavailable'
  else if (isAppleMobile() && !isStandalone()) state.value = 'homeScreen'
  else if (!supported()) state.value = 'unsupported'
  else if (Notification.permission === 'denied') state.value = 'denied'
  else state.value = (await subscription()) ? 'on' : 'off'
}

async function turnOn(): Promise<void> {
  if (busy.value || !publicKey) return
  busy.value = true
  try {
    const permission = await Notification.requestPermission()
    if (permission !== 'granted') {
      state.value = permission === 'denied' ? 'denied' : 'off'
      return
    }
    const registration = await navigator.serviceWorker.register(WORKER, { scope: SCOPE })
    await activated(registration)
    const created = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: keyBytes(publicKey),
    })
    try {
      await prepareCsrf()
      await savePushSubscription(created.toJSON())
    } catch (e) {
      // The BFF did not store it, so nothing would arrive: undo, and leave this browser as it was.
      await created.unsubscribe()
      await registration.unregister()
      throw e
    }
    state.value = 'on'
  } finally {
    busy.value = false
  }
}

async function turnOff(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const registration = await navigator.serviceWorker.getRegistration(SCOPE)
    const current = await registration?.pushManager.getSubscription()
    if (current) {
      await prepareCsrf()
      await forgetPushSubscription(current.endpoint)
      await current.unsubscribe()
    }
    await registration?.unregister()
    state.value = 'off'
  } finally {
    busy.value = false
  }
}

export function usePush() {
  return { state: readonly(state), busy: readonly(busy), load, turnOn, turnOff }
}
