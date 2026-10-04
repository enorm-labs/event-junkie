<script lang="ts" setup>
/**
 * The venue list's empty state when two or more features are chosen. Features combine with AND,
 * so naming that cause and offering to drop one feature is the way out (#2670).
 */
import { computed } from 'vue'
import { X } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { useFormat } from '@/composables/useFormat'
import { inCharacterOrder } from '@/lib/venueCharacters'

const props = defineProps<{ features: readonly string[] }>()

const emit = defineEmits<{ remove: [feature: string] }>()

const { t } = useI18n()
const { formatVenueCharacter } = useFormat()

const chosen = computed(() =>
  inCharacterOrder(props.features, (slug) => slug).map((slug) => ({
    slug,
    label: formatVenueCharacter(slug),
  })),
)
</script>

<template>
  <div class="space-y-3" data-testid="venue-features-empty">
    <p class="text-body text-muted-foreground">{{ t('venues.emptyCharacters') }}</p>
    <ul class="flex flex-wrap gap-2">
      <li v-for="feature in chosen" :key="feature.slug">
        <Button
          :aria-label="t('venues.removeCharacter', { feature: feature.label })"
          size="sm"
          type="button"
          variant="outline"
          @click="emit('remove', feature.slug)"
        >
          <X aria-hidden="true" />
          {{ feature.label }}
        </Button>
      </li>
    </ul>
  </div>
</template>
