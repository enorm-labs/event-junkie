<script lang="ts" setup>
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseDetailView from '@/components/BaseDetailView.vue'
import DetailLinks, { type DetailLink } from '@/components/DetailLinks.vue'
import { serviceIcons } from '@/lib/serviceIcons'
import CreditLine from '@/components/CreditLine.vue'
import { useArtist } from '@/composables/useArtist'
import { useDetailPage } from '@/composables/useDetailPage'
import { owesDiscogsCredit, textCredit } from '@/lib/imageCredit'
import { artistPageMeta } from '@/lib/pageMeta'
import { UNDERLINED_LINK_CLASS } from '@/lib/utils'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const { entity: artist, description, detail } = useDetailPage('artist', useArtist, artistPageMeta)

// A merged or renamed artist's old slug answers with the artist under its own slug (#4447). A
// replace, so Back skips the old URL, and the canonical and a share name one page.
const route = useRoute()
const router = useRouter()
watch(artist, (found) => {
  if (route.name === 'artist' && found?.slug && found.slug !== route.params.slug) {
    void router.replace({
      name: 'artist',
      params: { ...route.params, slug: found.slug },
      query: route.query,
      hash: route.hash,
    })
  }
})

const links = computed(() =>
  [
    // Listening first, since that is what most visitors came for; then the act's own channels,
    // then the reference sites. The brand labels stay as they are; only "Website" is a word. The
    // MusicBrainz link is the correction path ADR-031 chose: a wrong match is fixed there, not here.
    { label: 'Bandcamp', icon: serviceIcons.bandcamp, url: artist.value?.bandcampUrl },
    { label: 'SoundCloud', icon: serviceIcons.soundcloud, url: artist.value?.soundcloudUrl },
    { label: 'Spotify', icon: serviceIcons.spotify, url: artist.value?.spotifyUrl },
    { label: 'YouTube', icon: serviceIcons.youtube, url: artist.value?.youtubeUrl },
    {
      label: t('common.actions.website'),
      icon: serviceIcons.website,
      url: artist.value?.websiteUrl,
    },
    { label: 'Instagram', icon: serviceIcons.instagram, url: artist.value?.instagramUrl },
    { label: 'Facebook', icon: serviceIcons.facebook, url: artist.value?.facebookUrl },
    { label: 'Discogs', icon: serviceIcons.discogs, url: artist.value?.discogsUrl },
    {
      label: 'Resident Advisor',
      icon: serviceIcons.residentAdvisor,
      url: artist.value?.residentAdvisorUrl,
    },
    { label: 'MusicBrainz', icon: serviceIcons.musicbrainz, url: artist.value?.musicbrainzUrl },
  ].filter((link): link is DetailLink & typeof link => Boolean(link.url)),
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
          :class="UNDERLINED_LINK_CLASS"
          href="https://www.discogs.com"
          lang="en"
          rel="noopener"
          target="_blank"
          >Data provided by Discogs.</a
        >
      </p>
    </template>

    <template #description-credit>
      <CreditLine v-if="descriptionCredit" :credit="descriptionCredit" kind="text" />
    </template>
  </BaseDetailView>
</template>
