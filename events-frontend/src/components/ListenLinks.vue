<script lang="ts" setup>
// Plain outbound links, never an embed: a player would send the visitor's IP to Bandcamp or
// SoundCloud before any click (AGENTS.md § Privacy, #2723).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ArtistSummary } from '@/api/types'

const props = defineProps<{ artist: ArtistSummary | undefined }>()
const { t } = useI18n()

const links = computed(() =>
  [
    { service: 'Bandcamp', url: props.artist?.bandcampUrl },
    { service: 'SoundCloud', url: props.artist?.soundcloudUrl },
  ].filter((link): link is { service: string; url: string } => Boolean(link.url)),
)
</script>

<template>
  <span v-if="links.length" class="mt-1 flex flex-wrap gap-3 text-meta font-normal">
    <a
      v-for="link in links"
      :key="link.service"
      :aria-label="t('events.detail.listenOn', { act: artist?.name, service: link.service })"
      :href="link.url"
      class="text-muted-foreground underline underline-offset-2 hover:text-primary"
      rel="noopener noreferrer"
      target="_blank"
    >
      {{ link.service }}
    </a>
  </span>
</template>
