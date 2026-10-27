<script lang="ts" setup>
/**
 * The signed-in visitor's account (#3887): the address, push notifications for this browser (#3894), passkeys (#3888),
 * an export of everything stored (Art. 15 and 20 GDPR), sign-out, and deletion. A browser that is not signed in goes to the sign-in page.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'
import {
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogOverlay,
  AlertDialogPortal,
  AlertDialogRoot,
  AlertDialogTitle,
  AlertDialogTrigger,
} from 'reka-ui'
import ListPage from '@/components/ListPage.vue'
import NotificationSettings from '@/components/NotificationSettings.vue'
import PasskeySettings from '@/components/PasskeySettings.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import {
  deleteAccount,
  EXPORT_URL,
  fetchAccount,
  prepareCsrf,
  signOut,
  signOutEverywhere,
} from '@/api/account'
import { ApiError, describeError } from '@/api/client'
import type { Account } from '@/api/types'
import { useLocalePath } from '@/composables/useLocalePath'
import { setSession } from '@/composables/useSession'
import { berlinDateOf, formatLongDate } from '@/lib/format'

const { t, locale } = useI18n()
const router = useRouter()
const localePath = useLocalePath()

const account = ref<Account | null>(null)
const problem = ref('')
const busy = ref(false)
/** Set once the account is gone from this browser; the page then says so instead of the controls. */
const ended = ref<'signedOut' | 'deleted' | null>(null)

onMounted(async () => {
  try {
    const [, me] = await Promise.all([prepareCsrf(), fetchAccount()])
    account.value = me
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) await router.replace(localePath('/sign-in'))
    else problem.value = describeError(e, 'errors.subject.account')
  }
})

async function end(action: () => Promise<void>, outcome: 'signedOut' | 'deleted') {
  if (busy.value) return
  busy.value = true
  problem.value = ''
  try {
    await action()
    account.value = null
    setSession(null)
    ended.value = outcome
  } catch {
    problem.value = t('account.page.failed')
  } finally {
    busy.value = false
  }
}

function since(createdAt?: string): string {
  return createdAt ? formatLongDate(berlinDateOf(createdAt), locale.value) : ''
}
</script>

<template>
  <ListPage :title="t('pageTitle.account')">
    <div class="max-w-xl space-y-8">
      <p class="text-body text-destructive" role="alert">{{ problem }}</p>

      <div v-if="ended" class="space-y-4" role="status">
        <p class="text-body">{{ t(`account.page.${ended}`) }}</p>
        <Button as-child variant="outline">
          <RouterLink :to="localePath('/events')">{{ t('account.page.home') }}</RouterLink>
        </Button>
      </div>

      <p v-else-if="!account && !problem" class="text-body text-muted-foreground">
        {{ t('account.page.loading') }}
      </p>

      <template v-else-if="account">
        <div class="space-y-1">
          <p class="text-body font-medium">
            {{ t('account.page.signedInAs', { email: account.email }) }}
          </p>
          <p class="text-meta text-muted-foreground">
            {{ t('account.page.since', { date: since(account.createdAt) }) }}
          </p>
        </div>

        <Button as-child variant="outline">
          <RouterLink :to="localePath('/following')">{{ t('account.page.following') }}</RouterLink>
        </Button>

        <NotificationSettings />

        <PasskeySettings />

        <section class="space-y-3">
          <SectionLabel as="h2">{{ t('account.page.yourData') }}</SectionLabel>
          <p class="text-body text-muted-foreground">{{ t('account.page.exportHint') }}</p>
          <Button as="a" :href="EXPORT_URL" download variant="outline">
            {{ t('account.page.export') }}
          </Button>
        </section>

        <section class="space-y-3">
          <SectionLabel as="h2">{{ t('account.page.signOut') }}</SectionLabel>
          <div class="flex flex-wrap gap-3">
            <Button :disabled="busy" variant="outline" @click="end(signOut, 'signedOut')">
              {{ t('account.page.signOut') }}
            </Button>
            <Button :disabled="busy" variant="outline" @click="end(signOutEverywhere, 'signedOut')">
              {{ t('account.page.signOutEverywhere') }}
            </Button>
          </div>
        </section>

        <section class="space-y-3">
          <SectionLabel as="h2">{{ t('account.page.delete') }}</SectionLabel>
          <p class="text-body text-muted-foreground">{{ t('account.page.deleteHint') }}</p>
          <AlertDialogRoot>
            <AlertDialogTrigger as-child>
              <Button :disabled="busy" variant="destructive">{{ t('account.page.delete') }}</Button>
            </AlertDialogTrigger>
            <AlertDialogPortal>
              <AlertDialogOverlay class="fixed inset-0 z-40 bg-background/80" />
              <AlertDialogContent
                class="fixed inset-x-4 top-1/2 z-50 mx-auto max-w-md -translate-y-1/2 space-y-4 border border-border bg-background p-6"
              >
                <AlertDialogTitle class="text-section font-bold tracking-tight">
                  {{ t('account.page.deleteConfirmTitle') }}
                </AlertDialogTitle>
                <AlertDialogDescription class="text-body text-muted-foreground">
                  {{ t('account.page.deleteConfirmBody') }}
                </AlertDialogDescription>
                <div class="flex flex-wrap justify-end gap-3">
                  <AlertDialogCancel as-child>
                    <Button variant="outline">{{ t('account.page.cancel') }}</Button>
                  </AlertDialogCancel>
                  <AlertDialogAction as-child>
                    <Button variant="destructive" @click="end(deleteAccount, 'deleted')">
                      {{ t('account.page.deleteConfirm') }}
                    </Button>
                  </AlertDialogAction>
                </div>
              </AlertDialogContent>
            </AlertDialogPortal>
          </AlertDialogRoot>
        </section>
      </template>
    </div>
  </ListPage>
</template>
