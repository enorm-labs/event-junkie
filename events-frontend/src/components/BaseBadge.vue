<script lang="ts" setup>
/**
 * The small pill, and there are two of them left: the `beta` link in the header and a non-scheduled
 * status on an event. Everything else that wore one is a word in a meta line now (#1248), because a
 * row of five pills under a title reads as decoration even when each one is a fact.
 *
 * Variants rather than per-call-site classes, so a new pill cannot invent its own colour.
 */
import { cva, type VariantProps } from 'class-variance-authority'
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

const badgeVariants = cva('rounded-full px-2 py-0.5 text-meta font-medium', {
  variants: {
    variant: {
      /** Outlined, for a pill that is a link rather than a state. */
      outline: 'border border-border text-foreground/70',
      /** Cancelled, and the other non-scheduled statuses. */
      destructive: 'bg-destructive/10 text-destructive',
    },
  },
  defaultVariants: {
    variant: 'outline',
  },
})

type BadgeVariants = VariantProps<typeof badgeVariants>

const props = defineProps<{
  variant?: BadgeVariants['variant']
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <span :class="cn(badgeVariants({ variant }), props.class)">
    <slot />
  </span>
</template>
