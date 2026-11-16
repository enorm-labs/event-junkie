/** Lower case without accents and with single spaces, so `saal` finds Säälchen and `Mø ` equals `mo`. */
export function folded(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim().replace(/\s+/g, ' ')
}
