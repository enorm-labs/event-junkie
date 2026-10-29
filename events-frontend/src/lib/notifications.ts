/**
 * Sent on `window` once the notifications page has marked an entry or all of them read (#4169), so
 * the header's bell asks for its count again. The bell's own route-change ask can overtake the mark.
 */
export const NOTIFICATIONS_READ = 'ej:notifications-read'
