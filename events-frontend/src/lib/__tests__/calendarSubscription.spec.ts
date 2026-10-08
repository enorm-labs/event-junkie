import { describe, expect, it } from 'vitest'
import { calendarSubscription } from '@/lib/calendarSubscription'

describe('calendarSubscription', () => {
  const links = calendarSubscription(
    'https://event-junkie.de',
    '/calendar.ics?locale=en&genre=jazz&name=jazz',
    'jazz · Neukölln',
  )

  it('keeps the https address and swaps only the scheme for webcal', () => {
    expect(links.https).toBe('https://event-junkie.de/calendar.ics?locale=en&genre=jazz&name=jazz')
    expect(links.webcal).toBe(
      'webcal://event-junkie.de/calendar.ics?locale=en&genre=jazz&name=jazz',
    )
  })

  it('hands Google the webcal address as one encoded cid, so the filters survive', () => {
    const url = new URL(links.google)
    expect(url.origin + url.pathname).toBe('https://calendar.google.com/calendar/render')
    expect(url.searchParams.get('cid')).toBe(links.webcal)
    expect([...url.searchParams.keys()]).toEqual(['cid'])
  })

  it('hands Outlook.com the https address and the calendar name', () => {
    const url = new URL(links.outlook)
    expect(url.origin + url.pathname).toBe('https://outlook.live.com/calendar/0/addfromweb')
    expect(url.searchParams.get('url')).toBe(links.https)
    expect(url.searchParams.get('name')).toBe('jazz · Neukölln')
  })

  it('makes a webcal address from a plain http origin too, as in development', () => {
    expect(calendarSubscription('http://localhost:5173', '/calendar.ics', 'x').webcal).toBe(
      'webcal://localhost:5173/calendar.ics',
    )
  })
})
