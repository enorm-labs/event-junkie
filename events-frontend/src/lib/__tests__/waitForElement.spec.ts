import { afterEach, describe, expect, it } from 'vitest'

import { waitForElement } from '@/lib/waitForElement'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('waitForElement', () => {
  it('resolves at once with an element that is already there', async () => {
    document.body.innerHTML = '<section id="beta"></section>'
    expect(await waitForElement('#beta', 1000)).toBe(document.getElementById('beta'))
  })

  it('waits for an element a lazily loaded page renders later', async () => {
    setTimeout(() => {
      document.body.innerHTML = '<section id="beta"></section>'
    }, 50)
    expect(await waitForElement('#beta', 1000)).toBe(document.getElementById('beta'))
  })

  it('resolves with null when the element never appears', async () => {
    expect(await waitForElement('#beta', 50)).toBeNull()
  })
})
