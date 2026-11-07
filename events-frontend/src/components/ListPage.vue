<script lang="ts" setup>
/**
 * Shell for the list pages (events, calendar, map, venues, promoters, search): the page `main`,
 * its `h1` and an optional subtitle. The `header` slot adds lines under the subtitle; the default
 * slot is the page body.
 */
import { PAGE_TITLE_CLASS } from '@/lib/utils'

defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 :class="PAGE_TITLE_CLASS">{{ title }}</h1>
      <p v-if="subtitle" class="text-body text-muted-foreground">{{ subtitle }}</p>
      <slot name="header" />
    </header>
    <slot />
  </main>
</template>
