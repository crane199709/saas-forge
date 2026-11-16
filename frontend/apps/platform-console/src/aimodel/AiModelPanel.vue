<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import {
  EmptyState,
  ErrorAlert,
  PageHeader,
  PageToolbar,
  overlaySize,
  type ErrorTip,
} from '@crane199709/ui'
import type {
  AiModel,
  AiModelApi,
  AiModelCapability,
  AiModelProvider,
  AiModelStatus,
} from './ai-model-api'

const props = defineProps<{ api: AiModelApi; canManage: boolean }>()
const { t, te } = useI18n()
const models = ref<AiModel[]>([])
const search = ref('')
const status = ref('')
const filtered = computed(() =>
  models.value.filter(
    (model) =>
      model.name.toLowerCase().includes(search.value.trim().toLowerCase()) &&
      (!status.value || model.status === status.value),
  ),
)
const loading = ref(false)
const listError = ref<ErrorTip | null>(null)
function describeError(cause: unknown): ErrorTip {
  if (cause instanceof ApiError) {
    const key = `errorCode.${cause.code}`
    return {
      message: cause.userTip || (te(key) ? t(key) : t('error.unknown')),
      traceId: cause.traceId,
    }
  }
  return { message: t('error.unknown') }
}
async function load() {
  loading.value = true
  listError.value = null
  try {
    models.value = await props.api.list()
  } catch (cause) {
    listError.value = describeError(cause)
  } finally {
    loading.value = false
  }
}
const drawerOpen = ref(false)
const editing = ref<AiModel | null>(null)
const saving = ref(false)
const saveError = ref<ErrorTip | null>(null)
const capabilityOptions: AiModelCapability[] = ['CHAT', 'TOOLS', 'VISION', 'REASONING']
const providerOptions: AiModelProvider[] = ['DEEPSEEK', 'OPENAI_COMPATIBLE']
const emptyDraft = () => ({
  name: '',
  provider: 'DEEPSEEK' as AiModelProvider,
  modelId: '',
  baseUrl: '',
  capabilities: ['CHAT'] as AiModelCapability[],
  contextWindow: 1,
  apiKey: '',
})
const draft = ref(emptyDraft())
function openCreate() {
  if (!props.canManage) return
  editing.value = null
  draft.value = emptyDraft()
  saveError.value = null
  drawerOpen.value = true
}
function openEdit(model: AiModel) {
  if (!props.canManage) return
  editing.value = model
  draft.value = {
    name: model.name,
    provider: model.provider,
    modelId: model.modelId,
    baseUrl: model.baseUrl,
    capabilities: [...model.capabilities],
    contextWindow: model.contextWindow,
    apiKey: '',
  }
  saveError.value = null
  drawerOpen.value = true
}
async function save() {
  if (!props.canManage || saving.value) return
  const name = draft.value.name.trim()
  const modelId = draft.value.modelId.trim()
  const baseUrl = draft.value.baseUrl.trim()
  let validUrl = false
  try {
    const url = new URL(baseUrl)
    validUrl =
      url.protocol === 'https:' &&
      !!url.hostname &&
      !url.username &&
      !url.password &&
      !/^https:\/\/[^/]*@/i.test(baseUrl) &&
      !baseUrl.includes('?') &&
      !baseUrl.includes('#')
  } catch {
    /* 无效地址统一显示输入提示，不回显原始内容。 */
  }
  const invalid =
    !name || name.length > 120
      ? 'invalidName'
      : !/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$/.test(modelId)
        ? 'invalidModelId'
        : !validUrl
          ? 'invalidBaseUrl'
          : !draft.value.capabilities.length
            ? 'missingCapabilities'
            : !Number.isInteger(draft.value.contextWindow) || draft.value.contextWindow < 1
              ? 'invalidContextWindow'
              : (!editing.value || draft.value.apiKey !== '') &&
                  !/^[\x21-\x7e]{1,8192}$/.test(draft.value.apiKey)
                ? 'invalidApiKey'
                : null
  if (invalid) {
    saveError.value = { message: t(`aiModel.${invalid}`) }
    return
  }
  saving.value = true
  saveError.value = null
  try {
    const content = {
      name: draft.value.name.trim(),
      provider: draft.value.provider,
      modelId: draft.value.modelId.trim(),
      baseUrl: draft.value.baseUrl.trim(),
      capabilities: [...draft.value.capabilities],
      contextWindow: draft.value.contextWindow,
      status: editing.value?.status ?? ('ENABLED' as const),
      ...(draft.value.apiKey ? { apiKey: draft.value.apiKey } : {}),
    }
    if (editing.value) await props.api.update(editing.value.id, content)
    else await props.api.create(content)
    drawerOpen.value = false
    draft.value.apiKey = ''
    await load()
  } catch (cause) {
    saveError.value = describeError(cause)
  } finally {
    saving.value = false
  }
}
const confirming = ref(false)
const statusTarget = ref<AiModel | null>(null)
const nextStatus = ref<AiModelStatus>('DISABLED')
const statusSaving = ref(false)
const statusError = ref<ErrorTip | null>(null)
function confirmStatus(model: AiModel) {
  if (!props.canManage || statusSaving.value) return false
  statusTarget.value = model
  nextStatus.value = model.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  statusError.value = null
  confirming.value = true
  return false
}
async function changeStatus() {
  const model = statusTarget.value
  if (!model || !props.canManage || statusSaving.value) return
  statusSaving.value = true
  statusError.value = null
  try {
    await props.api.update(model.id, {
      name: model.name,
      provider: model.provider,
      modelId: model.modelId,
      baseUrl: model.baseUrl,
      capabilities: [...model.capabilities],
      contextWindow: model.contextWindow,
      status: nextStatus.value,
    })
    confirming.value = false
    await load()
  } catch (cause) {
    statusError.value = describeError(cause)
  } finally {
    statusSaving.value = false
  }
}
onMounted(load)
</script>

<template>
  <section :aria-label="t('aiModel.title')">
    <PageHeader :title="t('aiModel.title')" />
    <PageToolbar>
      <el-input
        id="ai-model-search"
        class="search-filter"
        v-model="search"
        :placeholder="t('aiModel.search')"
        :aria-label="t('aiModel.search')"
        clearable
      />
      <el-select
        v-model="status"
        data-testid="ai-model-status-filter"
        class="status-filter"
        :placeholder="t('aiModel.allStatuses')"
        :aria-label="t('aiModel.status')"
        clearable
      >
        <el-option
          v-for="value in ['ENABLED', 'DISABLED']"
          :key="value"
          :value="value"
          :label="t(`aiModel.statuses.${value}`)"
        />
      </el-select>
      <template #actions>
        <el-button
          v-if="canManage"
          type="primary"
          data-testid="ai-model-create"
          @click="openCreate"
          >{{ t('aiModel.create') }}</el-button
        >
      </template>
    </PageToolbar>
    <p v-if="loading" role="status">{{ t('aiModel.loading') }}</p>
    <div v-else-if="listError" class="page-error">
      <ErrorAlert :tip="listError" />
      <el-button data-testid="ai-model-retry" @click="load">{{ t('aiModel.retry') }}</el-button>
    </div>
    <el-table v-else :data="filtered" row-key="id">
      <template #empty
        ><EmptyState :title="models.length ? t('aiModel.noResults') : t('aiModel.empty')"
      /></template>
      <el-table-column
        prop="name"
        :label="t('aiModel.name')"
        min-width="160"
        show-overflow-tooltip
      />
      <el-table-column :label="t('aiModel.provider')" min-width="170">
        <template #default="{ row }">{{
          row.provider ? t(`aiModel.providers.${row.provider}`) : '—'
        }}</template>
      </el-table-column>
      <el-table-column :label="t('aiModel.modelId')" min-width="180" show-overflow-tooltip>
        <template #default="{ row }"
          ><code>{{ row.modelId }}</code></template
        >
      </el-table-column>
      <el-table-column :label="t('aiModel.capabilities')" min-width="240">
        <template #default="{ row }">
          <div class="capabilities">
            <el-tag v-for="capability in row.capabilities" :key="capability" size="small">{{
              t(`aiModel.capabilityNames.${capability}`)
            }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column
        prop="contextWindow"
        :label="t('aiModel.contextWindow')"
        width="140"
        align="right"
      />
      <el-table-column :label="t('aiModel.credential')" width="110">
        <template #default="{ row }">{{
          row.credentialKind ? t(`aiModel.credentials.${row.credentialKind}`) : '—'
        }}</template>
      </el-table-column>
      <el-table-column :label="t('aiModel.status')" width="110">
        <template #default="{ row }">
          <el-switch
            v-if="canManage"
            :model-value="row.status === 'ENABLED'"
            :before-change="() => confirmStatus(row as AiModel)"
            :loading="statusSaving && statusTarget?.id === row.id"
            :disabled="statusSaving"
            :aria-label="t('aiModel.statusOf', { name: row.name })"
            :data-testid="`ai-model-status-${row.id}`"
          />
          <el-tag v-else size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{
            row.status ? t(`aiModel.statuses.${row.status}`) : '—'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" :label="t('aiModel.actions')" width="90" fixed="right">
        <template #default="{ row }"
          ><el-button
            link
            type="primary"
            :data-testid="`ai-model-edit-${row.id}`"
            @click="openEdit(row as AiModel)"
            >{{ t('aiModel.edit') }}</el-button
          ></template
        >
      </el-table-column>
    </el-table>
    <el-drawer
      v-model="drawerOpen"
      :size="overlaySize.drawerLg"
      :title="editing ? t('aiModel.editTitle', { name: editing.name }) : t('aiModel.create')"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      @closed="draft.apiKey = ''"
    >
      <ErrorAlert v-if="saveError" :tip="saveError" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('aiModel.name')" for="ai-model-name" required>
          <el-input id="ai-model-name" v-model="draft.name" required maxlength="120" />
        </el-form-item>
        <el-form-item :label="t('aiModel.provider')" for="ai-model-provider" required>
          <el-select id="ai-model-provider" v-model="draft.provider">
            <el-option
              v-for="value in providerOptions"
              :key="value"
              :value="value"
              :label="t(`aiModel.providers.${value}`)"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('aiModel.modelId')" for="ai-model-model-id" required>
          <el-input
            id="ai-model-model-id"
            v-model="draft.modelId"
            required
            maxlength="200"
            pattern="[A-Za-z0-9][A-Za-z0-9._:\/\-]{0,199}"
            :placeholder="t('aiModel.modelIdPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('aiModel.baseUrl')" for="ai-model-base-url" required>
          <el-input
            id="ai-model-base-url"
            v-model="draft.baseUrl"
            type="url"
            required
            :placeholder="t('aiModel.baseUrlPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('aiModel.capabilities')" required>
          <el-checkbox-group v-model="draft.capabilities" :aria-label="t('aiModel.capabilities')">
            <el-checkbox v-for="value in capabilityOptions" :key="value" :value="value">{{
              t(`aiModel.capabilityNames.${value}`)
            }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item :label="t('aiModel.contextWindow')" for="ai-model-context-window" required>
          <el-input
            id="ai-model-context-window"
            v-model.number="draft.contextWindow"
            type="number"
            required
            min="1"
            step="1"
          />
        </el-form-item>
        <el-alert
          v-if="editing?.credentialKind === 'REFERENCE'"
          class="credential-hint"
          type="warning"
          :closable="false"
          :title="t('aiModel.referenceHint')"
        />
        <el-form-item :label="t('aiModel.apiKey')" for="ai-model-api-key" :required="!editing">
          <el-input
            id="ai-model-api-key"
            v-model="draft.apiKey"
            type="password"
            autocomplete="off"
            :required="!editing"
            maxlength="8192"
            :placeholder="
              editing ? t('aiModel.keepKeyPlaceholder') : t('aiModel.apiKeyPlaceholder')
            "
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving">{{
          t('aiModel.save')
        }}</el-button>
      </el-form>
    </el-drawer>
    <el-dialog
      v-model="confirming"
      :width="overlaySize.dialogSm"
      :title="
        nextStatus === 'DISABLED'
          ? t('aiModel.disableTitle', { name: statusTarget?.name })
          : t('aiModel.enableTitle', { name: statusTarget?.name })
      "
      :close-on-click-modal="!statusSaving"
      :close-on-press-escape="!statusSaving"
      :show-close="!statusSaving"
    >
      <p>
        {{ nextStatus === 'DISABLED' ? t('aiModel.disableImpact') : t('aiModel.enableImpact') }}
      </p>
      <ErrorAlert v-if="statusError" :tip="statusError" />
      <template #footer>
        <el-button
          data-testid="ai-model-status-cancel"
          :disabled="statusSaving"
          @click="confirming = false"
          >{{ t('aiModel.cancel') }}</el-button
        >
        <el-button
          type="primary"
          data-testid="ai-model-status-confirm"
          :loading="statusSaving"
          @click="changeStatus"
          >{{
            nextStatus === 'DISABLED' ? t('aiModel.confirmDisable') : t('aiModel.confirmEnable')
          }}</el-button
        >
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.search-filter {
  width: calc(var(--forge-space-5) * 10);
}
.status-filter {
  width: calc(var(--forge-space-5) * 6);
}
.credential-hint {
  margin-bottom: var(--forge-space-4);
}
.capabilities {
  display: flex;
  gap: var(--forge-space-1);
}
</style>
