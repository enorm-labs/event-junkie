<script lang="ts" setup>
/**
 * The "Our pick" pill: an event an operator features (#1262). One mark for the lead card's poster,
 * the compact row and the event page's poster (#3065). `small` fits it to a row's line of text.
 */
import { Sparkles } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import BaseBadge from '@/components/BaseBadge.vue'

withDefaults(defineProps<{ small?: boolean }>(), { small: false })

const { t } = useI18n()
</script>

<template>
  <BaseBadge
    variant="primary"
    :class="[
      'inline-flex items-center font-mono tracking-eyebrow uppercase',
      small ? 'gap-1 py-0 text-meta' : 'gap-1.5 text-body',
    ]"
    data-testid="pick-badge"
  >
    <Sparkles aria-hidden="true" :class="small ? 'size-3' : 'size-4'" />
    {{ t('events.card.ourPick') }}
  </BaseBadge>
</template>
