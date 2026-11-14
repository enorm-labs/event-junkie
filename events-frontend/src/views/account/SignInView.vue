<script lang="ts" setup>
/**
 * Asks for an address and sends a sign-in link (#3887). The address goes only into the POST body,
 * never into a URL, so no log and no history entry carries it. A passkey signs in without the mail (#3888):
 * from the button, or from the address field's autofill where the browser offers it.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BaseInput from '@/components/BaseInput.vue'
import ErrorLine from '@/components/ErrorLine.vue'
import ListPage from '@/components/ListPage.vue'
import { Button } from '@/components/ui/button'
import { fetchSessionState, prepareCsrf, requestLink, signInWithPasskey } from '@/api/account'
import { ApiError } from '@/api/client'
import { useLocalePath } from '@/composables/useLocalePath'
import { setSession } from '@/composables/useSession'
import { isLocale } from '@/i18n/locales'
import { signInIntent, type SignInIntent } from '@/lib/signInIntent'
import { signInNext } from '@/lib/signInNext'
import { SECTION_TITLE_CLASS, UNDERLINED_LINK_CLASS } from '@/lib/utils'
import { autofillSupported, cancelled, passkeysSupported } from '@/lib/webauthn'

const { t, locale } = useI18n()
const router = useRouter()
const route = useRoute()
// The page that sent the visitor here, such as a Follow button's (#3889). The mail link returns there.
const next = signInNext(route.query.next)
const localePath = useLocalePath()
// The button that sent the visitor here names the heading and the first benefit (#4968).
const intent = signInIntent(route.query.intent)
const heading = computed(() =>
  intent ? t(`account.signIn.heading.${intent}`) : t('pageTitle.signIn'),
)

const BENEFITS = ['follow', 'push', 'calendar', 'save'] as const
const FIRST: Record<SignInIntent, (typeof BENEFITS)[number]> = {
  follow: 'follow',
  rsvp: 'calendar',
  save: 'save',
}
const benefits = intent
  ? [FIRST[intent], ...BENEFITS.filter((b) => b !== FIRST[intent])]
  : [...BENEFITS]

const email = ref('')
const sending = ref(false)
const problem = ref('')
// False while an operator has paused sign-up (#3906). Every address is still answered alike.
const signUpOpen = ref(true)
const passkeys = passkeysSupported()
// The autofill request waits until the visitor picks a passkey; the button and leaving the page end it.
let autofill: AbortController | null = null

onMounted(async () => {
  // A visitor who is already signed in has nothing to do here.
  const [, session] = await Promise.allSettled([prepareCsrf(), fetchSessionState()])
  if (session.status !== 'fulfilled') return
  signUpOpen.value = session.value.signUpOpen
  if (session.value.account) await router.replace(next ?? localePath('/following'))
  else if (await autofillSupported()) {
    autofill = new AbortController()
    void withPasskey('conditional', autofill.signal)
  }
})

onBeforeUnmount(() => autofill?.abort())

async function withPasskey(mediation?: CredentialMediationRequirement, signal?: AbortSignal) {
  if (!mediation) autofill?.abort()
  problem.value = ''
  try {
    setSession(await signInWithPasskey(mediation, signal))
    await router.replace(next ?? localePath('/following'))
  } catch (e) {
    // The autofill starts with the page, so its failure is not the visitor's doing: the button still works.
    if (cancelled(e) || mediation === 'conditional') return
    problem.value = passkeyProblem(e)
  }
}

function passkeyProblem(e: unknown): string {
  if (e instanceof ApiError && e.status === 429) return t('account.signIn.passkeyTooMany')
  if (e instanceof ApiError && e.status === 404) return t('account.signIn.passkeyUnknown')
  return t('account.signIn.passkeyFailed')
}

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
  <ListPage :title="heading">
    <div class="grid gap-8 md:grid-cols-2">
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
            autocomplete="username webauthn"
            class="w-full"
            inputmode="email"
            name="email"
            required
            type="email"
          />
        </div>
        <!-- Announced when it appears, so a screen reader hears why nothing happened. -->
        <ErrorLine id="sign-in-problem">{{ problem }}</ErrorLine>
        <div class="flex flex-wrap gap-3">
          <Button :disabled="sending" type="submit">
            {{ sending ? t('account.signIn.sending') : t('account.signIn.submit') }}
          </Button>
          <Button v-if="passkeys" type="button" variant="outline" @click="withPasskey()">
            {{ t('account.signIn.passkey') }}
          </Button>
        </div>
        <p class="text-meta text-muted-foreground">
          {{ t('account.signIn.privacy') }}
          <RouterLink :to="localePath('/legal/privacy#account')" :class="UNDERLINED_LINK_CLASS">
            {{ t('account.signIn.privacyLink') }}
          </RouterLink>
        </p>
      </form>
      <!-- A paused sign-up cannot give a new address any of this (#3906). -->
      <section v-if="signUpOpen" aria-labelledby="sign-in-benefits" class="max-w-md space-y-3">
        <h2 id="sign-in-benefits" :class="SECTION_TITLE_CLASS">
          {{ t('account.signIn.benefits.title') }}
        </h2>
        <ul class="list-disc space-y-2 ps-5 text-body" data-testid="sign-in-benefits">
          <li v-for="benefit in benefits" :key="benefit">
            {{ t(`account.signIn.benefits.${benefit}`) }}
          </li>
        </ul>
        <p class="text-body text-muted-foreground">{{ t('account.signIn.benefits.trust') }}</p>
      </section>
    </div>
  </ListPage>
</template>
