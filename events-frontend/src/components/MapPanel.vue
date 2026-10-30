<script lang="ts" setup>
import { X } from '@lucide/vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { MAP_PANEL_CLASS } from '@/lib/utils'

/** The panel a pick lays over a `VenueMap`: a heading, a close button, and the body in the slot. */
defineProps<{
  title?: string
  /** Where the heading links, for a panel that stands for one venue. */
  to?: RouteLocationRaw
  subtitle?: string
}>()

defineEmits<{ close: [] }>()

const { t } = useI18n()
</script>

<template>
  <section aria-live="polite" :class="MAP_PANEL_CLASS">
    <div class="flex items-start gap-2">
      <div class="min-w-0 flex-1">
        <h2 class="truncate text-card-title font-bold tracking-tight">
          <RouterLink v-if="to" :to="to" class="hover:text-primary">{{ title }}</RouterLink>
          <template v-else>{{ title }}</template>
        </h2>
        <p v-if="subtitle" class="truncate text-meta text-muted-foreground">{{ subtitle }}</p>
      </div>
      <Button
        :aria-label="t('map.closePanel')"
        :title="t('map.closePanel')"
        size="icon-xs"
        type="button"
        variant="ghost"
        @click="$emit('close')"
      >
        <X aria-hidden="true" />
      </Button>
    </div>
    <slot />
  </section>
</template>
