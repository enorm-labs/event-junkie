<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { PromoterListItem } from '@/api/types'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import type { Locale } from '@/i18n/locales'
import { descriptionFor } from '@/lib/description'
import { CARD_CLASS } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    promoter: PromoterListItem
    /** Heading level for the card's name — see the same prop on `EventCard.vue` for why. */
    as?: 'h2' | 'h3' | 'h4'
  }>(),
  { as: 'h3' },
)

const { locale } = useI18n()
const localePath = useLocalePath()

// The same language choice the promoter page makes, so a card never contradicts its page.
const description = computed(() => descriptionFor(props.promoter, locale.value as Locale))

const { formatUpcoming } = useFormat()
const upcoming = computed(() =>
  formatUpcoming({ ...props.promoter, upcomingEventCount: props.promoter.upcomingEventCount ?? 0 }),
)

/** The site's host, which is what a reader recognises; the scheme and path are noise on a card. */
const websiteHost = computed(() => {
  const url = props.promoter.websiteUrl
  if (!url) return null
  try {
    return new URL(url).host.replace(/^www\./, '')
  } catch {
    return url
  }
})
</script>

<template>
  <article :class="CARD_CLASS">
    <div class="min-w-0 space-y-1">
      <component :is="as" class="truncate text-lede font-semibold">
        <RouterLink
          :to="localePath(`/promoters/${promoter.slug}`)"
          class="underline-offset-4 hover:underline"
        >
          {{ promoter.name }}
        </RouterLink>
      </component>
      <p
        v-if="description"
        :lang="description.lang ?? undefined"
        class="line-clamp-2 text-body text-muted-foreground"
      >
        {{ description.text }}
      </p>
      <p class="text-body text-muted-foreground">
        <template v-for="(part, i) in upcoming" :key="part">
          <template v-if="i">{{ ' ' }}</template>
          <span class="whitespace-nowrap">{{ i < upcoming.length - 1 ? `${part} ·` : part }}</span>
        </template>
      </p>
      <a
        v-if="websiteHost"
        :href="promoter.websiteUrl ?? undefined"
        class="block truncate text-body text-primary underline-offset-4 hover:underline"
        rel="noopener noreferrer"
        target="_blank"
      >
        {{ websiteHost }}
      </a>
    </div>
  </article>
</template>
