<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { tenantClientKey, type InvitationPreview } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import AuthCard from '../AuthCard.vue'
import { errorTip, type ErrorTip } from '../error-tip'

const { t } = useI18n()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const token = String(useRoute().query.invitation ?? '')
const preview = ref<InvitationPreview | null>(null)
const accepted = ref(false)
const account = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

onMounted(async () => {
  try {
    preview.value = await client.previewInvitation(token)
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
})

// 接受后不自动登录：已有身份用原账号登录，新设置凭证的用刚设置的账号登录，进入租户的流程与普通登录一致。
async function accept() {
  busy.value = true
  error.value = null
  try {
    await client.acceptInvitation(
      preview.value?.requiresCredentials
        ? {
            token,
            account: account.value,
            password: password.value,
            ...(displayName.value ? { displayName: displayName.value } : {}),
          }
        : { token },
    )
    accepted.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthCard :title="t('invitation.title')">
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <p v-if="accepted" data-testid="invitation-accepted">
      {{ t('invitation.accepted', { tenant: preview?.tenantName }) }}
    </p>
    <el-form v-else-if="preview" label-position="top" @submit.prevent="accept">
      <p>{{ t('invitation.prompt', { tenant: preview.tenantName }) }}</p>
      <template v-if="preview.requiresCredentials">
        <el-form-item :label="t('register.account')" for="invitation-account">
          <el-input
            id="invitation-account"
            v-model="account"
            name="invitation-account"
            autocomplete="username"
            required
          />
        </el-form-item>
        <el-form-item :label="t('register.password')" for="invitation-password">
          <el-input
            id="invitation-password"
            v-model="password"
            name="invitation-password"
            type="password"
            autocomplete="new-password"
            required
            show-password
          />
        </el-form-item>
        <el-form-item :label="t('register.displayName')" for="invitation-display-name">
          <el-input
            id="invitation-display-name"
            v-model="displayName"
            name="invitation-display-name"
          />
        </el-form-item>
      </template>
      <el-button native-type="submit" type="primary" :loading="busy">{{
        t('invitation.accept')
      }}</el-button>
    </el-form>
  </AuthCard>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
</style>
