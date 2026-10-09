<script lang="ts" setup>
import { computed } from 'vue'
import BaseDetailView from '@/components/BaseDetailView.vue'
import DetailLinks, { type DetailLink } from '@/components/DetailLinks.vue'
import TextCreditLine from '@/components/TextCreditLine.vue'
import { useArtist } from '@/composables/useArtist'
import { useDetailPage } from '@/composables/useDetailPage'
import { owesDiscogsCredit, textCredit } from '@/lib/imageCredit'
import { artistPageMeta } from '@/lib/pageMeta'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const { entity: artist, description, detail } = useDetailPage('artist', useArtist, artistPageMeta)

const links = computed(() =>
  [
    // The brand labels stay as they are; only "Website" is a word. The MusicBrainz link is the
    // correction path ADR-031 chose: a wrong match is fixed there, not here.
    { label: t('common.actions.website'), url: artist.value?.websiteUrl },
    { label: 'Bandcamp', url: artist.value?.bandcampUrl },
    { label: 'SoundCloud', url: artist.value?.soundcloudUrl },
    { label: 'Spotify', url: artist.value?.spotifyUrl },
    { label: 'YouTube', url: artist.value?.youtubeUrl },
    { label: 'Instagram', url: artist.value?.instagramUrl },
    { label: 'Facebook', url: artist.value?.facebookUrl },
    { label: 'Discogs', url: artist.value?.discogsUrl },
    { label: 'Resident Advisor', url: artist.value?.residentAdvisorUrl },
    { label: 'MusicBrainz', url: artist.value?.musicbrainzUrl },
  ].filter((link): link is DetailLink => Boolean(link.url)),
)

// Discogs' terms prescribe the wording, so the line stays English on the German page too.
const discogsCredit = computed(() => owesDiscogsCredit(artist.value))

const descriptionCredit = computed(() =>
  description.value ? textCredit(artist.value, description.value.side) : null,
)
</script>

<template>
  <BaseDetailView v-bind="detail">
    <template #meta>
      <DetailLinks :links="links" />
      <p v-if="discogsCredit" class="mt-2 text-meta text-muted-foreground">
        <a
          class="underline underline-offset-2"
          href="https://www.discogs.com"
          lang="en"
          rel="noopener"
          target="_blank"
          >Data provided by Discogs.</a
        >
      </p>
    </template>

    <div v-if="description" class="space-y-2">
      <p
        :lang="description.lang ?? undefined"
        class="text-prose whitespace-pre-line wrap-anywhere text-foreground/90"
      >
        {{ description.text }}
      </p>
      <TextCreditLine v-if="descriptionCredit" :credit="descriptionCredit" />
    </div>
  </BaseDetailView>
</template>
