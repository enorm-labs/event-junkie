/**
 * Berlin wall-clock time for a `datetime-local` input, which has no time zone. The importer stores
 * an instant, so the editor converts both ways.
 */

const berlinParts = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
  timeZone: 'Europe/Berlin',
})

function partsAt(ms: number): Record<string, string> {
  return Object.fromEntries(berlinParts.formatToParts(ms).map((part) => [part.type, part.value]))
}

/** Berlin's offset from UTC at [ms], in milliseconds: one hour in winter, two in summer. */
function berlinOffset(ms: number): number {
  const p = partsAt(ms)
  const wall = Date.UTC(+p.year!, +p.month! - 1, +p.day!, +p.hour!, +p.minute!, +p.second!)
  return wall - Math.floor(ms / 1000) * 1000
}

/** [instant] as Berlin time, `YYYY-MM-DDTHH:mm`, or '' for none. */
export function toBerlinLocal(instant: string | null | undefined): string {
  if (!instant) return ''
  const p = partsAt(Date.parse(instant))
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`
}

/** The ISO instant of [local], a `YYYY-MM-DDTHH:mm` Berlin time. */
export function fromBerlinLocal(local: string): string {
  const [date, time] = local.split('T')
  const [year, month, day] = date!.split('-').map(Number)
  const [hour, minute] = time!.split(':').map(Number)
  const wall = Date.UTC(year!, month! - 1, day!, hour!, minute!)
  // The offset at the guess can differ from the offset at the answer near a DST change: check it twice.
  const first = wall - berlinOffset(wall)
  return new Date(wall - berlinOffset(first)).toISOString()
}

/** [instant] for a table cell, `YYYY-MM-DD HH:mm` in Berlin. */
export function formatBerlin(instant: string): string {
  return toBerlinLocal(instant).replace('T', ' ')
}
