import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import { signInAction, signInNext, signInReturn } from '@/lib/signInNext'

describe('signInNext', () => {
  it('takes a page of this site, alone or with the closed query', () => {
    for (const next of [
      '/de/venues/sisyphos',
      '/en/events/tonight?do=rsvp-going',
      '/de/artists/ada?do=follow',
      '/en/events?genre=techno&genre=house&q=Caf%C3%A9+Nacht&from=2026-11-20&do=save',
    ])
      expect(signInNext(next)).toBe(next)
  })

  it('rejects any other key, any other action, an absolute URL and //host', () => {
    for (const next of [
      '/de/events?next=//evil.example',
      '/de/events?genre=techno&utm_source=mail',
      '/de/events?do=unfollow',
      '/de/events?do=follow&do=delete-account',
      '/de/events?',
      '/de/events?genre=',
      '/de/events?genre=a b',
      '/de/events?genre=a#b',
      'https://evil.example/de/events?do=follow',
      '//evil.example/de/events?do=follow',
      '/fr/events?do=follow',
      '/de/events?do=follow\n',
      42,
      undefined,
    ])
      expect(signInNext(next)).toBeNull()
  })

  it('caps the query at 1100 characters', () => {
    const fits = `/de/events?q=${'a'.repeat(1098)}`
    expect(signInNext(fits)).toBe(fits)
    expect(signInNext(`${fits}a`)).toBeNull()
  })
})

describe('signInReturn', () => {
  it('adds the action to the path and the query', () => {
    expect(signInReturn('/en/venues/sisyphos', 'follow')).toBe('/en/venues/sisyphos?do=follow')
    expect(signInReturn('/en/events', 'save', new URLSearchParams('genre=techno'))).toBe(
      '/en/events?genre=techno&do=save',
    )
  })

  it('returns to the bare path when the search is too long to carry', () => {
    const query = new URLSearchParams(
      Array.from({ length: 60 }, (_, i) => ['venue', `a-venue-with-a-long-slug-${i}`]),
    )
    expect(signInReturn('/en/events', 'save', query)).toBe('/en/events')
  })

  it("survives the router's round trip: a + in the next is a space on the page", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:rest(.*)', component: defineComponent({ render: () => h('p') }) }],
    })
    const next = signInReturn('/en/events', 'save', new URLSearchParams({ q: 'a+b c' }))
    await router.push({ path: '/en/sign-in', query: { next } })
    expect(signInNext(router.currentRoute.value.query.next)).toBe(next)
    await router.replace(next)
    expect(router.currentRoute.value.query).toEqual({ q: 'a+b c', do: 'save' })
  })
})

describe('signInAction', () => {
  it('names a known action only', () => {
    expect(signInAction('rsvp-interested')).toBe('rsvp-interested')
    expect(signInAction('unfollow')).toBeNull()
    expect(signInAction(['follow'])).toBeNull()
  })
})
