<script lang="ts" setup>
/**
 * The page the sign-in mail links to (#3887). The token arrives in the fragment and is taken out of
 * the address bar at once, so it stays out of the history. Signing in needs a click: a mail scanner
 * that opens the link only loads this page and cannot use the token up.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import ListPage from '@/components/ListPage.vue'
import { Button } from '@/components/ui/button'
import { prepareCsrf, signIn } from '@/api/account'
import { ApiError, describeError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { setSession } from '@/composables/useSession'
import { signInNext } from '@/lib/signInNext'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const localePath = useLocalePath()

const token = new URLSearchParams(route.hash.slice(1)).get('token') ?? ''
// Read before the address bar is cleaned below, which drops the query with the fragment.
const next = signInNext(route.query.next)
const signingIn = ref(false)
// Used, expired or missing: only a new link helps. Any other failure keeps the button for a retry.
const spent = ref(!token)
const problem = ref(token ? '' : t('account.confirm.missing'))

onMounted(() => {
  if (route.hash) history.replaceState(history.state, '', route.path)
  void prepareCsrf().catch(() => undefined)
})

async function confirm() {
  if (signingIn.value) return
  signingIn.value = true
  problem.value = ''
  try {
    setSession(await signIn(token))
    await router.replace(next ?? localePath('/following'))
  } catch (e) {
    spent.value = e instanceof ApiError && e.status === 401
    problem.value = spent.value ? t('account.confirm.invalid') : describeError(e)
  } finally {
    signingIn.value = false
  }
}
</script>

<template>
  <ListPage :title="t('pageTitle.confirmSignIn')">
    <div class="max-w-md space-y-4">
      <p v-if="!spent" class="text-body">{{ t('account.confirm.body') }}</p>
      <p class="text-body text-destructive" role="alert">{{ problem }}</p>
      <Button v-if="!spent" :disabled="signingIn" @click="confirm">
        {{ signingIn ? t('account.confirm.signingIn') : t('account.confirm.submit') }}
      </Button>
      <Button v-if="spent" as-child variant="outline">
        <RouterLink :to="localePath('/sign-in')">{{ t('account.confirm.again') }}</RouterLink>
      </Button>
    </div>
  </ListPage>
</template>
