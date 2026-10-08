/**
 * Where a calendar subscription can go. Android registers no `webcal:` handler, so a tap on that
 * link does nothing there (#2887); Google and Outlook.com take the address through their own pages.
 * These are outbound links the visitor chooses, as `googleCalendarUrl` in `addToCalendar.ts` is.
 */
export interface CalendarSubscription {
  /** The `https:` address, for an app that asks for one. */
  https: string
  /** The calendar app the operating system registers, Apple Calendar or Outlook among them. */
  webcal: string
  google: string
  outlook: string
}

/** The subscription links for the site-relative `path` of a calendar, served from `origin`. */
export function calendarSubscription(
  origin: string,
  path: string,
  name: string,
): CalendarSubscription {
  const https = `${origin}${path}`
  const webcal = https.replace(/^https?:/, 'webcal:')
  const outlook = new URLSearchParams({ url: https, name })
  return {
    https,
    webcal,
    google: `https://calendar.google.com/calendar/render?cid=${encodeURIComponent(webcal)}`,
    outlook: `https://outlook.live.com/calendar/0/addfromweb?${outlook}`,
  }
}
