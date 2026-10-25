<script lang="ts" setup>
/**
 * Follow or unfollow an artist, venue or promoter (#3889). Signed out, it is a link to sign-in that
 * comes back to this page. Signed in, a toggle between "Follow" and "Following": a press on
 * "Following" unfollows. It changes at once and goes back with a message if the BFF refuses.
 */
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import { Check, Plus } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { follow, type FollowKind, isFollowing, prepareCsrf, unfollow } from '@/api/account'
import { ApiError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSession } from '@/composables/useSession'

// `initiallyFollowing` defaults to undefined, not to Vue's false for an absent boolean: undefined means "ask".
const props = withDefaults(
  defineProps<{
    kind: FollowKind
    slug: string
    /** Known already, as on the Following page's list: the button then asks nothing. */
    initiallyFollowing?: boolean
    /** No link to the Following page after a follow: the page itself. */
    noHint?: boolean
  }>(),
  { initiallyFollowing: undefined },
)
const emit = defineEmits<{ change: [following: boolean] }>()

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const { account } = useSession()

const following = ref<boolean | null>(null)
const busy = ref(false)
const problem = ref('')
/** Set by a follow in this visit, so the hint answers "where does it go?" when the question arises. */
const justFollowed = ref(false)
let csrfReady: Promise<void> | null = null

watch(
  [account, () => props.slug],
  async ([signedIn]) => {
    following.value = null
    problem.value = ''
    justFollowed.value = false
    if (!signedIn) return
    if (props.initiallyFollowing !== undefined) {
      following.value = props.initiallyFollowing
      return
    }
    try {
      following.value = await isFollowing(props.kind, props.slug)
    } catch {
      following.value = false
    }
  },
  { immediate: true },
)

async function toggle() {
  if (busy.value || following.value === null) return
  const was = following.value
  busy.value = true
  problem.value = ''
  following.value = !was
  try {
    csrfReady ??= prepareCsrf()
    await csrfReady
    await (was ? unfollow : follow)(props.kind, props.slug)
    justFollowed.value = !was
    emit('change', !was)
  } catch (e) {
    following.value = was
    problem.value =
      e instanceof ApiError && e.status === 409
        ? t('account.follow.tooMany')
        : t('account.follow.failed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="account !== undefined" class="flex flex-wrap items-center gap-x-3 gap-y-1">
    <Button v-if="!account" as-child variant="outline">
      <RouterLink :to="{ path: localePath('/sign-in'), query: { next: route.path } }">
        <Plus data-icon="inline-start" />
        {{ t('account.follow.follow') }}
      </RouterLink>
    </Button>
    <!-- The label says the state, "Follow" or "Following", as on most sites. Screen readers also hear
         what a press does on a followed entry, which the visible label leaves out. -->
    <Button
      v-else-if="following !== null"
      :disabled="busy"
      :variant="following ? 'secondary' : 'outline'"
      @click="toggle"
    >
      <Check v-if="following" data-icon="inline-start" />
      <Plus v-else data-icon="inline-start" />
      {{ following ? t('account.follow.following') : t('account.follow.follow') }}
      <span v-if="following" class="sr-only">{{ t('account.follow.unfollowHint') }}</span>
    </Button>
    <p v-if="problem" class="text-meta text-destructive" role="alert">{{ problem }}</p>
    <p v-else-if="!noHint && justFollowed" class="text-meta text-muted-foreground" role="status">
      <RouterLink class="underline underline-offset-2" :to="localePath('/following')">
        {{ t('account.follow.shown') }}
      </RouterLink>
    </p>
  </div>
</template>
