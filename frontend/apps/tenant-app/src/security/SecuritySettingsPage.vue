<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError, useTenantClient } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createSecurityApi } from './security-api'

const { t } = useI18n()
const api = createSecurityApi(useTenantClient())
const mfaRequired = ref<boolean | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const currentIp = ref<string | null>(null)
const rules = ref<string[]>([])
const rowErrors = ref<Record<number, string>>({})
const saved = ref(false)

function removeRule(index: number) {
  rules.value.splice(index, 1)
  rowErrors.value = {}
  saved.value = false
}
function addRule() {
  rules.value.push('')
  saved.value = false
}
async function saveRules() {
  rowErrors.value = {}
  saved.value = false
  await run(async () => {
    try {
      const result = await api.updateIpAllowlist(rules.value)
      rules.value = result.rules
      currentIp.value = result.currentIp
      saved.value = true
    } catch (cause) {
      if (cause instanceof ApiError) {
        for (const item of cause.errors ?? []) {
          const index = /^rules\[(\d+)\]$/.exec(item.field)
          if (index) rowErrors.value[Number(index[1])] = item.userTip?.trim() || t('error.unknown')
        }
      }
      throw cause
    }
  })
  // 等 busy 解除、输入框恢复可用后再定位，禁用状态的输入框不能接收焦点。
  await nextTick()
  const first = Object.keys(rowErrors.value)[0]
  if (first !== undefined) document.getElementById(`ip-rule-${first}`)?.focus()
}

async function run(task: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await task()
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

onMounted(() =>
  run(async () => {
    mfaRequired.value = (await api.get()).mfaRequired
    const allowlist = await api.getIpAllowlist()
    currentIp.value = allowlist.currentIp
    rules.value = allowlist.rules
  }),
)

// 开关以服务端返回的值为准，保存失败时保持原状态。
function toggle(next: string | number | boolean) {
  return run(async () => {
    mfaRequired.value = (await api.update({ mfaRequired: next === true })).mfaRequired
  })
}
</script>

<template>
  <section class="security-settings" :aria-label="t('securitySettings.title')">
    <h2>{{ t('securitySettings.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />
    <el-card v-if="mfaRequired !== null">
      <div class="row">
        <span>{{ t('securitySettings.mfaRequired') }}</span>
        <el-switch
          :model-value="mfaRequired"
          :loading="busy"
          data-testid="mfa-required"
          @update:model-value="toggle"
        />
      </div>
      <p class="hint">{{ t('securitySettings.mfaRequiredHint') }}</p>
    </el-card>
    <el-card v-if="currentIp !== null" class="ip-card">
      <h3>租户 IP 允许列表</h3>
      <p>
        当前 IP：<strong>{{ currentIp }}</strong>
      </p>
      <p class="hint">
        保存后立即影响所有成员进入当前租户及每次请求，不影响全局登录或其他租户。必须包含当前
        IP；空列表表示无限制。
      </p>
      <p class="hint">支持 IPv4、IPv6 和 CIDR，最多 100 条，不允许 /0。</p>
      <el-form label-position="top" @submit.prevent="saveRules">
        <el-form-item
          v-for="(_, index) in rules"
          :key="index"
          :label="`条目 ${index + 1}`"
          :for="`ip-rule-${index}`"
          :error="rowErrors[index]"
          :data-testid="`ip-rule-${index}`"
        >
          <div class="rule-row">
            <el-input
              :id="`ip-rule-${index}`"
              v-model="rules[index]"
              :disabled="busy"
              @input="saved = false"
            />
            <el-button
              :disabled="busy"
              :aria-label="`删除条目 ${index + 1}`"
              @click="removeRule(index)"
              >删除</el-button
            >
          </div>
        </el-form-item>
        <p v-if="rules.length === 0">当前未限制来源 IP。</p>
        <div class="row">
          <el-button :disabled="busy || rules.length >= 100" @click="addRule">添加条目</el-button>
          <el-button native-type="submit" type="primary" :loading="busy" data-testid="ip-save"
            >保存 IP 允许列表</el-button
          >
        </div>
        <p v-if="saved" role="status">租户 IP 允许列表已保存。</p>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>
.security-settings {
  max-width: 640px;
}
.ip-card {
  margin-top: 20px;
}
.rule-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.row {
  display: flex;
  gap: 16px;
  align-items: center;
}
.hint {
  color: var(--el-text-color-secondary);
}
@media (max-width: 640px) {
  .rule-row {
    flex-direction: column;
    align-items: stretch;
  }
  .row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .row .el-button + .el-button {
    margin-left: 0;
  }
}
</style>
