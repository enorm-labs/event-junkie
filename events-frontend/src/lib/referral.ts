/**
 * Tags an outbound link to a venue or ticket shop, so the venue's analytics counts the visit as
 * ours (#2770). Every outbound link carries `rel="noreferrer"`, so without the tag it reads as
 * direct. The parameters name this site, never the visitor.
 *
 * Artist platform links stay untagged: they are not the venue's analytics.
 */
const REFERRAL_QUERY = 'utm_source=event-junkie.de&utm_medium=referral'

export function withReferral(href: string): string {
  let url: URL
  try {
    url = new URL(href)
  } catch {
    return href
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return href
  // A link the venue already tagged keeps its own campaign.
  if ([...url.searchParams.keys()].some((key) => key.toLowerCase().startsWith('utm_'))) return href
  // Appended as text: rewriting through searchParams would re-encode the shop's own query.
  url.search = url.search ? `${url.search}&${REFERRAL_QUERY}` : REFERRAL_QUERY
  return url.toString()
}
