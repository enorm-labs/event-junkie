import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it, vi } from 'vitest'

/**
 * `public/push-sw.js` is plain JavaScript that Vite copies as it is, so it is run here from its text with a fake
 * `self`: the options it hands to `showNotification` are what the device sees (#4167).
 */
const source = readFileSync(resolve(__dirname, '../../public/push-sw.js'), 'utf8')

function push(payload: unknown) {
  const listeners: Record<string, (event: unknown) => void> = {}
  const showNotification = vi
    .fn<(title: string, options: NotificationOptions & { renotify?: boolean }) => Promise<void>>()
    .mockResolvedValue(undefined)
  const self = {
    addEventListener: (type: string, listener: (event: unknown) => void) => {
      listeners[type] = listener
    },
    registration: { showNotification },
  }
  new Function('self', source)(self)
  listeners.push!({ data: { json: () => payload }, waitUntil: () => undefined })
  const [title, options] = showNotification.mock.calls[0]!
  return { title, options }
}

describe('push-sw.js', () => {
  it('passes the kind as the tag, so a new push replaces the open one of its kind', () => {
    const { title, options } = push({
      title: 'New date',
      body: 'Moderat',
      url: '/en/following',
      tag: 'new-dates',
    })

    expect(title).toBe('New date')
    expect(options).toMatchObject({
      tag: 'new-dates',
      renotify: true,
      data: { url: '/en/following' },
    })
  })

  it('shows a push without a tag on its own, as before', () => {
    const { options } = push({ title: 'New date', body: 'Moderat', url: '/en/following' })

    expect(options).not.toHaveProperty('tag')
    expect(options).not.toHaveProperty('renotify')
  })

  it('ignores a tag that is not a word', () => {
    expect(push({ title: 'x', url: '/', tag: 42 }).options).not.toHaveProperty('tag')
    expect(push({ title: 'x', url: '/', tag: '' }).options).not.toHaveProperty('tag')
  })
})
