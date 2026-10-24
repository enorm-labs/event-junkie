<script lang="ts" setup>
/** After a link was requested. Says the same whether or not the address has an account (#3887). */
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import ListPage from '@/components/ListPage.vue'
import { Button } from '@/components/ui/button'
import { useLocalePath } from '@/composables/useLocalePath'

const { t } = useI18n()
const localePath = useLocalePath()
</script>

<template>
  <ListPage :title="t('pageTitle.checkMail')">
    <div class="max-w-md space-y-4">
      <p class="text-body">{{ t('account.checkMail.body') }}</p>
      <p class="text-body text-muted-foreground">{{ t('account.checkMail.nothing') }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/sign-in')">{{ t('account.checkMail.again') }}</RouterLink>
      </Button>
    </div>
  </ListPage>
</template>
