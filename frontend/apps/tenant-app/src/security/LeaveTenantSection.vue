<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createMemberApi } from '../member/member-api'
import { reloadShellKey } from '../shell'

const { t } = useI18n()
const client = useTenantClient()
const reload = inject(reloadShellKey, () => location.assign('/'))
const tenantName = client.session()?.tenantName ?? ''
const leaving = ref(false)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 离开后当前 Token 随版本递增失效，不能再用它换发其他租户的 Token；结束会话回到登录，有其他租户的身份重新登录后选择。
async function leave() {
  busy.value = true
  error.value = null
  try {
    await createMemberApi(client).leave()
  } catch (cause) {
    error.value = errorTip(cause, t)
    busy.value = false
    leaving.value = false
    return
  }
  try {
    await client.logout()
  } catch {
    // 本地凭证已清除；IAM 会话退出失败不影响已完成的离开租户。
  }
  void reload()
}
</script>

<template>
  <section data-testid="danger-zone">
    <h3>{{ t('account.danger.title') }}</h3>
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <div class="row">
      <span>{{ tenantName }}</span>
      <el-button plain type="danger" data-testid="tenant-leave" @click="leaving = true">{{
        t('tenant.leave')
      }}</el-button>
    </div>
    <el-dialog v-model="leaving" :width="overlaySize.dialogSm" :title="t('tenant.leave')">
      <p>{{ t('tenant.leaveConfirm', { tenant: tenantName }) }}</p>
      <template #footer>
        <el-button @click="leaving = false">{{ t('tenant.cancel') }}</el-button>
        <el-button
          type="danger"
          :disabled="busy"
          data-testid="tenant-leave-confirm"
          @click="leave"
          >{{ t('tenant.leave') }}</el-button
        >
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
h3 {
  margin: 0 0 var(--forge-space-3);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--forge-space-4);
}
</style>
