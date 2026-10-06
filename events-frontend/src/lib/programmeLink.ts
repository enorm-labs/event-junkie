/**
 * Where a programme link points, so the venue page can say so: the venue's own site, its Resident
 * Advisor page, or a ticket platform (#2766). Read from the host alone; the link is never fetched.
 */
export type ProgrammeLink =
  | { kind: 'own' }
  | { kind: 'resident-advisor' }
  | { kind: 'platform'; platform: string }

/** Ticket platforms a venue may sell its programme on, by host, with the name they go by. */
const PLATFORMS: Record<string, string> = {
  'eventbrite.com': 'Eventbrite',
  'eventbrite.de': 'Eventbrite',
  'dice.fm': 'DICE',
  'eventim.de': 'Eventim',
  'reservix.de': 'Reservix',
  'yesticket.org': 'YesTicket',
  'tixforgigs.com': 'tixforgigs',
}

/** The kind of page `url` is; a URL that does not parse counts as the venue's own. */
export function programmeLink(url: string): ProgrammeLink {
  let host: string
  try {
    host = new URL(url).hostname.toLowerCase()
  } catch {
    return { kind: 'own' }
  }
  const matches = (domain: string) => host === domain || host.endsWith(`.${domain}`)
  if (matches('ra.co')) return { kind: 'resident-advisor' }
  const platform = Object.keys(PLATFORMS).find(matches)
  return platform ? { kind: 'platform', platform: PLATFORMS[platform]! } : { kind: 'own' }
}
