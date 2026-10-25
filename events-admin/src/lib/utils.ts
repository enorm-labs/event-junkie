import type { ClassValue } from 'clsx'
import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * A table's actions column, which sticks to the right edge so the other columns scroll under it.
 * Whole class names, so Tailwind finds them here.
 */
export const STICKY_HEAD = 'sticky right-0 z-10 shadow-[inset_1px_0_0_var(--color-border)] bg-muted'

/** The cell below {@link STICKY_HEAD}: a sticky cell needs a solid background, and a hovered row mixes its tint at 50 %. */
export const STICKY_CELL =
  'sticky right-0 z-10 shadow-[inset_1px_0_0_var(--color-border)] bg-background ' +
  '[tr:hover>&]:bg-[color-mix(in_oklab,var(--color-muted)_50%,var(--color-background))]'
