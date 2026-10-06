/**
 * A route to a venue in the visitor's maps app (#2767). No single URL opens the default maps app
 * everywhere, so the link depends on the platform, and OpenStreetMap's router is the answer for
 * everything else, including a crawler and a visitor without JavaScript.
 */

export type MapsPlatform = 'apple' | 'android' | 'web'

/**
 * An iPad on iPadOS 13 and later reports itself as a Mac, which is fine: both open Apple Maps.
 * Android is tested first because some Android browsers name "like Mac OS X" too.
 */
export function mapsPlatform(userAgent: string | null | undefined): MapsPlatform {
  if (!userAgent) return 'web'
  if (/Android/i.test(userAgent)) return 'android'
  if (/iPhone|iPad|iPod|Macintosh|Mac OS X/i.test(userAgent)) return 'apple'
  return 'web'
}

/** `encodeURIComponent` leaves parentheses alone, and `geo:` reads them as the label's delimiters. */
function encodeLabel(name: string): string {
  return encodeURIComponent(name).replace(/\(/g, '%28').replace(/\)/g, '%29')
}

export function directionsUrl(
  position: { latitude: number; longitude: number },
  name: string | null | undefined,
  platform: MapsPlatform,
): string {
  const at = `${position.latitude},${position.longitude}`
  const label = name ? encodeLabel(name) : ''
  switch (platform) {
    case 'apple':
      return `https://maps.apple.com/?daddr=${at}${label ? `&q=${label}` : ''}`
    case 'android':
      return `geo:${at}?q=${at}${label ? `(${label})` : ''}`
    case 'web':
      return `https://www.openstreetmap.org/directions?to=${at}`
  }
}

let detected: MapsPlatform | undefined

/** The browser's platform, read from `navigator.userAgent` once per page load. */
export function clientMapsPlatform(): MapsPlatform {
  detected ??= mapsPlatform(typeof navigator === 'undefined' ? undefined : navigator.userAgent)
  return detected
}
