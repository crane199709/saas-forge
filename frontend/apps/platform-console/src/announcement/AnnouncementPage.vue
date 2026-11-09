<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { vLoading } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageHeader, formatDateTime, overlaySize, type ErrorTip } from '@crane199709/ui'
import { usePlatformClient } from '../shell'
import { errorTip } from '../error-tip'
import { createAnnouncementApi, type Announcement } from './announcement-api'
const client = usePlatformClient()
const api = createAnnouncementApi(client)
const { t } = useI18n()
const items = ref<Announcement[]>([])
const busy = ref(false)
const loading = ref(false)
const error = ref<ErrorTip | null>(null)
const formError = ref<ErrorTip | null>(null)
const creating = ref(false)
const pending = ref<Announcement | null>(null)
const viewing = ref<Announcement | null>(null)
const title = ref('')
const body = ref('')
const subApp = ref('')
const route = ref('')
const expiresAt = ref('')
const allTenants = ref(true)
const tenantIds = ref('')
const targets = computed(() => [...new Set(tenantIds.value.split(/[\s,，]+/).filter(Boolean))])
const targetsValid = computed(
  () =>
    allTenants.value ||
    (targets.value.length > 0 &&
      targets.value.every((id) =>
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id),
      )),
)

const linkValid = computed(
  () =>
    (!subApp.value && !route.value) ||
    (/^[a-z][a-z0-9-]{0,63}$/.test(subApp.value) &&
      /^\/(?!\/)/.test(route.value) &&
      !route.value.includes('\\')),
)
const valid = computed(
  () => title.value.trim() && body.value.trim() && linkValid.value && targetsValid.value,
)
let generation = 0
let active = true
async function load() {
  const started = ++generation
  loading.value = true
  error.value = null
  try {
    const result = await api.list()
    if (active && started === generation) items.value = result
  } catch (cause) {
    if (active && started === generation) error.value = errorTip(cause, t)
  } finally {
    if (active && started === generation) loading.value = false
  }
}
function create() {
  title.value = ''
  body.value = ''
  subApp.value = ''
  route.value = ''
  expiresAt.value = ''
  allTenants.value = true
  tenantIds.value = ''
  formError.value = null
  creating.value = true
}
async function publish() {
  if (!valid.value || busy.value) return
  busy.value = true
  formError.value = null
  try {
    await api.publish({
      title: title.value.trim(),
      body: body.value.trim(),
      subApp: subApp.value || null,
      route: route.value || null,
      expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null,
      allTenants: allTenants.value,
      tenantIds: allTenants.value ? [] : targets.value,
    })
    if (!active) return
    creating.value = false
    await load()
  } catch (cause) {
    if (active) formError.value = errorTip(cause, t)
  } finally {
    if (active) busy.value = false
  }
}
function requestRevoke(item: Announcement) {
  pending.value = item
  formError.value = null
}
async function revoke() {
  if (!pending.value || busy.value) return
  busy.value = true
  formError.value = null
  try {
    await api.revoke(pending.value.id)
    if (!active) return
    pending.value = null
    await load()
  } catch (cause) {
    if (active) formError.value = errorTip(cause, t)
  } finally {
    if (active) busy.value = false
  }
}
function status(item: Announcement) {
  return item.revoked
    ? '已撤回'
    : item.expiresAt && Date.parse(item.expiresAt) <= Date.now()
      ? '已到期'
      : '发布中'
}
function tagOf(item: Announcement) {
  return status(item) === '发布中' ? 'success' : 'info'
}
onMounted(() => void load())
onUnmounted(() => {
  active = false
  generation += 1
})
</script>
<template>
  <section class="announcement-management" aria-label="公告管理">
    <PageHeader title="公告管理">
      <template #actions>
        <el-button :disabled="loading" @click="load">刷新公告</el-button>
        <el-button type="primary" data-testid="announcement-create" @click="create"
          >发布公告</el-button
        >
      </template>
    </PageHeader>
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <el-table v-loading="loading" :data="items" row-key="id" empty-text="暂无公告">
      <el-table-column label="标题" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span :data-testid="`announcement-${row.id}`">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="body" label="内容" min-width="220" show-overflow-tooltip />
      <el-table-column label="投放范围" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{
          row.allTenants ? '全部租户' : `指定租户：${row.tenantIds.join('、')}`
        }}</template>
      </el-table-column>
      <el-table-column label="状态" width="88">
        <template #default="{ row }">
          <el-tag size="small" :type="tagOf(row as Announcement)">{{
            status(row as Announcement)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
      </el-table-column>
      <el-table-column label="到期时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
      </el-table-column>
      <el-table-column :label="'操作'" width="136" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewing = row as Announcement">查看</el-button>
          <el-button
            v-if="!row.revoked"
            link
            type="danger"
            :data-testid="`announcement-revoke-${row.id}`"
            @click="requestRevoke(row as Announcement)"
            >撤回公告</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      :model-value="viewing !== null"
      :title="viewing?.title ?? ''"
      :width="overlaySize.dialogMd"
      @close="viewing = null"
    >
      <p class="body">{{ viewing?.body }}</p>
    </el-dialog>
    <el-drawer
      v-model="creating"
      title="发布公告"
      :size="overlaySize.drawerMd"
      :close-on-click-modal="false"
      :show-close="!busy"
      :close-on-press-escape="!busy"
    >
      <el-form label-position="top" @submit.prevent="publish">
        <el-form-item label="标题"
          ><el-input
            v-model="title"
            aria-label="标题"
            maxlength="200"
            data-testid="announcement-title"
        /></el-form-item>
        <el-form-item label="正文（纯文本）"
          ><el-input
            v-model="body"
            aria-label="正文（纯文本）"
            type="textarea"
            :rows="5"
            maxlength="20000"
            data-testid="announcement-body"
        /></el-form-item>
        <el-form-item label="投放范围">
          <el-radio-group v-model="allTenants" aria-label="投放范围"
            ><el-radio :value="true">全部租户</el-radio
            ><el-radio :value="false">指定租户</el-radio></el-radio-group
          >
        </el-form-item>
        <el-form-item v-if="!allTenants" label="租户标识（UUID）"
          ><el-input
            v-model="tenantIds"
            type="textarea"
            :rows="3"
            aria-label="租户标识（UUID）"
            placeholder="多个标识用逗号或换行分隔"
          />
          <p v-if="!targetsValid" class="hint">请填写至少一个有效的租户 UUID。</p></el-form-item
        >
        <el-form-item label="到期时间（可选）" for="announcement-expiry"
          ><input
            id="announcement-expiry"
            v-model="expiresAt"
            type="datetime-local"
            aria-label="到期时间（可选）"
        /></el-form-item>
        <el-form-item label="Sub-App 编码（可选）"
          ><el-input
            v-model="subApp"
            aria-label="Sub-App 编码（可选）"
            maxlength="64"
            placeholder="例如 demo"
        /></el-form-item>
        <el-form-item label="业务路由（可选）"
          ><el-input
            v-model="route"
            aria-label="业务路由（可选）"
            maxlength="2048"
            placeholder="例如 /overview"
          />
          <p v-if="!linkValid" class="hint">
            业务链接须同时填写 Sub-App 编码和以单个 / 开头的站内路由。
          </p></el-form-item
        >
      </el-form>
      <ErrorAlert v-if="formError" :tip="formError" />
      <template #footer
        ><el-button :disabled="busy" @click="creating = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="busy || !valid"
          data-testid="announcement-publish"
          @click="publish"
          >确认发布</el-button
        ></template
      >
    </el-drawer>
    <el-dialog
      :model-value="pending !== null"
      title="撤回公告"
      :width="overlaySize.dialogSm"
      :show-close="!busy"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      @close="pending = null"
    >
      <p>撤回「{{ pending?.title }}」后，成员将无法再看到这条公告。</p>
      <ErrorAlert v-if="formError" :tip="formError" />
      <template #footer
        ><el-button :disabled="busy" @click="pending = null">取消</el-button
        ><el-button
          type="danger"
          :disabled="busy"
          data-testid="announcement-confirm-revoke"
          @click="revoke"
          >确认撤回</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>
<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.body {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
}
input[type='datetime-local'] {
  max-width: 100%;
  padding: var(--forge-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--forge-radius-sm);
  font: inherit;
}
</style>
