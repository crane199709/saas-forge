<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, overlaySize, type ErrorTip } from '@crane199709/ui'
import type { SubApp, SubAppApi } from './sub-app-api'

const props = defineProps<{ api: SubAppApi }>()
// 注册表变化时通知 Shell，套餐面板据此提供可选的 Sub-App 编码
const emit = defineEmits<{ loaded: [apps: SubApp[]] }>()
const { t } = useI18n()
const apps = ref<SubApp[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

interface Pair {
  key: string
  value: string
}

// 新建与编辑共用一个对话框：editing 为 null 时新建；编码只在新建时填写。键值对以行编辑，提交时转为对象。
const dialogOpen = ref(false)
const editing = ref<SubApp | null>(null)
const emptyDraft = () => ({
  code: '',
  name: '',
  version: '',
  entries: [{ key: 'local', value: '' }] as Pair[],
  requirements: [] as Pair[],
})
const draft = ref(emptyDraft())

const pairs = (record: Record<string, string>): Pair[] =>
  Object.entries(record).map(([key, value]) => ({ key, value }))
const record = (rows: Pair[]) =>
  Object.fromEntries(
    rows
      .map((row) => [row.key.trim(), row.value.trim()])
      .filter(([key, value]) => key !== '' || value !== ''),
  )
const describe = (values: Record<string, string>, separator: string) =>
  Object.entries(values)
    .map(([key, value]) => `${key}${separator}${value}`)
    .join('；')

async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await action()
    return true
  } catch (cause) {
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('error.unknown'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
    return false
  } finally {
    busy.value = false
  }
}

async function fetchApps() {
  apps.value = await props.api.list()
  emit('loaded', apps.value)
}

function open(app: SubApp | null) {
  error.value = null
  editing.value = app
  draft.value = app
    ? {
        code: app.code,
        name: app.name,
        version: app.version,
        entries: pairs(app.remoteEntries),
        requirements: pairs(app.sharedRequirements),
      }
    : emptyDraft()
  dialogOpen.value = true
}

async function save() {
  const app = editing.value
  const content = {
    name: draft.value.name.trim(),
    version: draft.value.version.trim(),
    remoteEntries: record(draft.value.entries),
    sharedRequirements: record(draft.value.requirements),
  }
  const done = await run(async () => {
    if (app) await props.api.update(app.id, content)
    else await props.api.create({ code: draft.value.code.trim(), ...content })
    await fetchApps()
  })
  if (done) dialogOpen.value = false
}

const remove = (app: SubApp) =>
  run(async () => {
    await props.api.remove(app.id)
    await fetchApps()
  })

onMounted(() => run(fetchApps))
</script>

<template>
  <section :aria-label="t('subApp.title')">
    <PageHeader :title="t('subApp.title')">
      <template #actions>
        <el-button
          type="primary"
          :loading="busy"
          data-testid="sub-app-create"
          @click="open(null)"
          >{{ t('subApp.create') }}</el-button
        >
      </template>
    </PageHeader>
    <ErrorAlert v-if="error && !dialogOpen" class="page-error" :tip="error" />

    <el-table :data="apps" row-key="code" :empty-text="t('subApp.empty')">
      <el-table-column
        prop="name"
        :label="t('subApp.name')"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="code"
        :label="t('subApp.code')"
        min-width="120"
        show-overflow-tooltip
      />
      <el-table-column prop="version" :label="t('subApp.version')" width="90" />
      <el-table-column :label="t('subApp.entries')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ describe(row.remoteEntries, '：') }}</template>
      </el-table-column>
      <el-table-column :label="t('subApp.requirements')" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ describe(row.sharedRequirements, ' ≥ ') }}</template>
      </el-table-column>
      <el-table-column :label="t('subApp.actions')" width="112" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="busy"
            :data-testid="`sub-app-edit-${row.code}`"
            @click="open(row as SubApp)"
            >{{ t('subApp.edit') }}</el-button
          >
          <el-button
            link
            type="danger"
            :disabled="busy"
            :data-testid="`sub-app-delete-${row.code}`"
            @click="remove(row as SubApp)"
            >{{ t('subApp.delete') }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="dialogOpen"
      :size="overlaySize.drawerLg"
      :title="editing ? t('subApp.editTitle', { name: editing.name }) : t('subApp.create')"
    >
      <ErrorAlert v-if="error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('subApp.code')" for="sub-app-code">
          <el-input
            id="sub-app-code"
            v-model="draft.code"
            :disabled="editing !== null"
            :placeholder="t('subApp.codeHint')"
            maxlength="64"
            required
          />
        </el-form-item>
        <el-form-item :label="t('subApp.name')" for="sub-app-name">
          <el-input id="sub-app-name" v-model="draft.name" maxlength="120" required />
        </el-form-item>
        <el-form-item :label="t('subApp.version')" for="sub-app-version">
          <el-input id="sub-app-version" v-model="draft.version" maxlength="64" required />
        </el-form-item>
        <el-form-item :label="t('subApp.entries')">
          <div class="rows">
            <div v-for="(entry, index) in draft.entries" :key="index" class="pair">
              <el-input
                v-model="entry.key"
                :aria-label="t('subApp.environment')"
                :placeholder="t('subApp.environment')"
                class="key"
                :data-testid="`sub-app-env-${index}`"
              />
              <el-input
                v-model="entry.value"
                :aria-label="t('subApp.remoteEntry')"
                :placeholder="t('subApp.remoteEntry')"
                :data-testid="`sub-app-entry-${index}`"
              />
              <el-button link type="danger" @click="draft.entries.splice(index, 1)">{{
                t('subApp.removeRow')
              }}</el-button>
            </div>
            <el-button link type="primary" @click="draft.entries.push({ key: '', value: '' })">{{
              t('subApp.addEntry')
            }}</el-button>
          </div>
        </el-form-item>
        <el-form-item :label="t('subApp.requirements')">
          <div class="rows">
            <div v-for="(requirement, index) in draft.requirements" :key="index" class="pair">
              <el-input
                v-model="requirement.key"
                :aria-label="t('subApp.dependency')"
                :placeholder="t('subApp.dependency')"
                class="key"
                :data-testid="`sub-app-dep-${index}`"
              />
              <el-input
                v-model="requirement.value"
                :aria-label="t('subApp.minVersion')"
                placeholder="3.5.0"
                :data-testid="`sub-app-min-${index}`"
              />
              <el-button link type="danger" @click="draft.requirements.splice(index, 1)">{{
                t('subApp.removeRow')
              }}</el-button>
            </div>
            <el-button
              link
              type="primary"
              @click="draft.requirements.push({ key: '', value: '' })"
              >{{ t('subApp.addRequirement') }}</el-button
            >
          </div>
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('subApp.save')
        }}</el-button>
      </el-form>
    </el-drawer>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.hint {
  margin: var(--forge-space-1) 0 0;
  color: var(--el-text-color-secondary);
}
.rows {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-2);
  width: 100%;
}
.pair {
  display: flex;
  gap: var(--forge-space-2);
}
.pair .key {
  flex: 0 0 160px;
}
</style>
