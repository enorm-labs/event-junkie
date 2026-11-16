<script lang="ts" setup>
/**
 * The search page's offer when no artist matched (#5214): an act that is not on the site yet can be watched, and the visitor
 * hears of its first Berlin date. Nothing is asked of MusicBrainz before the press. Signed out, the button leads to sign-in,
 * which comes back here with `do=watch` and opens the picker.
 */
import { BellPlus } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import SectionLabel from '@/components/SectionLabel.vue'
import WatchPicker from '@/components/WatchPicker.vue'
import { Button } from '@/components/ui/button'
import { useSession } from '@/composables/useSession'
import { useSignInAction } from '@/composables/useSignInAction'
import { useLocalePath } from '@/composables/useLocalePath'
import { signInReturn } from '@/lib/signInNext'

const props = defineProps<{ q: string }>()

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const { account } = useSession()
const signInAction = useSignInAction()

const open = ref(false)

/** Signed out: sign-in, then back to this search with `do=watch`. */
const signInLink = computed(() => ({
  path: localePath('/sign-in'),
  query: {
    next: signInReturn(route.path, 'watch', new URLSearchParams({ q: props.q })),
    intent: 'follow',
  },
}))

// Back from the sign-in this offer led to: the picker opens, as the press would have.
watch(
  account,
  (signedIn) => {
    if (signedIn && signInAction.take('watch')) open.value = true
  },
  { immediate: true },
)

// Another term is another search: the picker closes, and the next press starts from the new term.
watch(
  () => props.q,
  () => {
    open.value = false
  },
)
</script>

<template>
  <section v-if="account !== undefined" class="space-y-3">
    <SectionLabel as="h2">{{ t('account.watch.offer') }}</SectionLabel>
    <p class="text-body text-muted-foreground">
      {{ q ? t('account.watch.offerText', { q }) : t('account.watch.offerTextAny') }}
    </p>
    <Button v-if="!account" as-child variant="outline">
      <RouterLink :to="signInLink" rel="nofollow">
        <BellPlus data-icon="inline-start" />
        {{ t('account.watch.open') }}
      </RouterLink>
    </Button>
    <Button v-else-if="!open" variant="outline" @click="open = true">
      <BellPlus data-icon="inline-start" />
      {{ t('account.watch.open') }}
    </Button>
    <template v-else>
      <WatchPicker :key="q" :initial-query="q" />
      <Button size="sm" variant="ghost" @click="open = false">
        {{ t('account.watch.close') }}
      </Button>
    </template>
  </section>
</template>
