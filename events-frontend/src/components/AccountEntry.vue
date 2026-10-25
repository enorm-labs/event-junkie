<script lang="ts" setup>
/**
 * The header's way into an account (#3889): "Sign in" for a visitor who is not signed in, and
 * "Following" for one who is. The Following page links on to the account. One icon button, as the
 * other header controls are, so it fits the header at every width.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { UserRound, UserRoundCheck } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSession } from '@/composables/useSession'

const { t } = useI18n()
const localePath = useLocalePath()
const { account } = useSession()

const label = computed(() => t(account.value ? 'common.nav.following' : 'common.nav.signIn'))
</script>

<template>
  <!-- `title` is the hover tooltip only; `aria-label` wins for the accessible name. -->
  <Button :aria-label="label" :title="label" as-child size="icon" variant="outline">
    <RouterLink :to="localePath(account ? '/following' : '/sign-in')">
      <UserRoundCheck v-if="account" />
      <UserRound v-else />
    </RouterLink>
  </Button>
</template>
