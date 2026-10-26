<script lang="ts" setup>
/**
 * Asks for an address and sends a sign-in link (#3887). The address goes only into the POST body,
 * never into a URL, so no log and no history entry carries it.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BaseInput from '@/components/BaseInput.vue'
import ListPage from '@/components/ListPage.vue'
import { Button } from '@/components/ui/button'
import { fetchSessionState, prepareCsrf, requestLink } from '@/api/account'
import { ApiError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { isLocale } from '@/i18n/locales'
import { signInNext } from '@/lib/signInNext'

const { t, locale } = useI18n()
const router = useRouter()
const route = useRoute()
// The page that sent the visitor here, such as a Follow button's (#3889). The mail link returns there.
const next = signInNext(route.query.next)
const localePath = useLocalePath()

const email = ref('')
const sending = ref(false)
const problem = ref('')
// False while an operator has paused sign-up (#3906). Every address is still answered alike.
const signUpOpen = ref(true)

onMounted(async () => {
  // A visitor who is already signed in has nothing to do here.
  const [, session] = await Promise.allSettled([prepareCsrf(), fetchSessionState()])
  if (session.status !== 'fulfilled') return
  signUpOpen.value = session.value.signUpOpen
  if (session.value.account) await router.replace(next ?? localePath('/following'))
})

async function submit() {
  if (sending.value) return
  // The BFF checks the address too; an empty field needs no round trip to say so.
  if (!email.value.trim()) {
    problem.value = t('account.signIn.invalid')
    return
  }
  sending.value = true
  problem.value = ''
  try {
    await requestLink(
      email.value.trim(),
      isLocale(locale.value) ? locale.value : 'de',
      next ?? undefined,
    )
    await router.push(localePath('/sign-in/check-mail'))
  } catch (e) {
    problem.value = messageFor(e)
  } finally {
    sending.value = false
  }
}

function messageFor(e: unknown): string {
  if (e instanceof ApiError && e.status === 400) return t('account.signIn.invalid')
  if (e instanceof ApiError && e.status === 429) return t('account.signIn.tooMany')
  return t('account.signIn.mailDown')
}
</script>

<template>
  <ListPage :title="t('pageTitle.signIn')">
    <form class="max-w-md space-y-4" novalidate @submit.prevent="submit">
      <p class="text-body text-muted-foreground">{{ t('account.signIn.intro') }}</p>
      <p v-if="!signUpOpen" class="text-body" role="status">{{ t('account.signIn.paused') }}</p>
      <div class="space-y-2">
        <label class="block text-body font-medium" for="sign-in-email">
          {{ t('account.signIn.emailLabel') }}
        </label>
        <BaseInput
          id="sign-in-email"
          v-model="email"
          :aria-describedby="problem ? 'sign-in-problem' : undefined"
          :aria-invalid="problem ? 'true' : undefined"
          autocomplete="email"
          class="w-full"
          inputmode="email"
          name="email"
          required
          type="email"
        />
      </div>
      <!-- Announced when it appears, so a screen reader hears why nothing happened. -->
      <p id="sign-in-problem" class="text-body text-destructive" role="alert">{{ problem }}</p>
      <Button :disabled="sending" type="submit">
        {{ sending ? t('account.signIn.sending') : t('account.signIn.submit') }}
      </Button>
      <p class="text-meta text-muted-foreground">
        {{ t('account.signIn.privacy') }}
        <RouterLink :to="localePath('/legal/privacy#account')" class="underline underline-offset-4">
          {{ t('account.signIn.privacyLink') }}
        </RouterLink>
      </p>
    </form>
  </ListPage>
</template>
