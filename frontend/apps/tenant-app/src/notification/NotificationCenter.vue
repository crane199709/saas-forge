<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createNotificationApi, type InboxItem, type InboxTab } from './notification-api'
import { useInboxUnread } from './unread-count'
const client = useTenantClient()
const api = createNotificationApi(client)
const unread = useInboxUnread()
const router = useRouter()
const { t } = useI18n()
const tabs = { notifications: '通知', announcements: '公告', todos: '待办' }
const tab = ref<InboxTab>('notifications')
const items = ref<InboxItem[]>([])
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
let generation = 0
let unsubscribe: (() => void) | undefined
async function load(number = page.value) {
  const started = ++generation
  const selected = tab.value
  items.value = []
  error.value = null
  busy.value = true
  try {
    if (selected === 'announcements') {
      const result = await api.announcements()
      if (started !== generation) return
      items.value = result
      total.value = result.length
      page.value = 1
    } else {
      const result = await api[selected](number)
      if (started !== generation) return
      items.value = result.items
      page.value = result.page.number
      total.value = result.page.totalElements
    }
    void unread.refresh()
  } catch (cause) {
    if (started === generation) error.value = errorTip(cause, t)
  } finally {
    if (started === generation) busy.value = false
  }
}
function select(kind: InboxTab) {
  tab.value = kind
  total.value = 0
  void load(1)
}
async function markRead(item?: InboxItem) {
  if (tab.value === 'todos') return
  const started = generation
  busy.value = true
  error.value = null
  try {
    if (item) await api.read(tab.value, item.id)
    else await api.readAll()
    if (started === generation) await load()
  } catch (cause) {
    if (started === generation) error.value = errorTip(cause, t)
  } finally {
    if (started === generation) busy.value = false
  }
}
function linkOf(item: InboxItem) {
  const link =
    item.link ?? (item.subApp && item.route ? { appCode: item.subApp, route: item.route } : null)
  return link ? `/apps/${link.appCode}${link.route}` : null
}
function open(item: InboxItem) {
  const path = linkOf(item)
  if (path) void router.push(path)
}
onMounted(() => {
  let membership = client.session()?.membershipId
  unsubscribe = client.onSessionChange((session) => {
    if (membership === session?.membershipId) return
    membership = session?.membershipId
    generation += 1
    items.value = []
    total.value = 0
    error.value = null
    busy.value = false
    if (session) void load(1)
  })
  void load(1)
})
onUnmounted(() => {
  generation += 1
  unsubscribe?.()
})
</script>
<template>
  <section class="notification-center" aria-label="通知中心">
    <h2>通知中心</h2>
    <div class="toolbar" role="tablist" aria-label="通知类型">
      <el-button
        v-for="(label, kind) in tabs"
        :key="kind"
        role="tab"
        :aria-selected="tab === kind"
        :type="tab === kind ? 'primary' : 'default'"
        :data-testid="`tab-${kind}`"
        @click="select(kind)"
        >{{ label }}</el-button
      >
    </div>
    <div class="toolbar">
      <el-button :disabled="busy" @click="load()">刷新列表</el-button>
      <el-button
        v-if="tab === 'notifications'"
        :disabled="busy || !items.length"
        @click="markRead()"
        >全部通知标为已读</el-button
      >
    </div>
    <ErrorAlert v-if="error" :tip="error" />
    <p v-if="busy" role="status">加载中…</p>
    <p v-else-if="!error && !items.length">暂无{{ tabs[tab] }}</p>
    <article
      v-for="item in items"
      :key="item.id"
      class="inbox-item"
      :data-testid="`inbox-${item.id}`"
    >
      <h3>
        {{ item.title }}
        <el-tag v-if="tab !== 'todos'" size="small" :type="item.read ? 'info' : 'warning'">{{
          item.read ? '已读' : '未读'
        }}</el-tag>
      </h3>
      <p class="body">{{ item.body }}</p>
      <time>{{ item.occurredAt ?? item.publishedAt }}</time>
      <div class="item-actions">
        <el-button
          v-if="tab !== 'todos' && !item.read"
          :disabled="busy"
          :data-testid="`mark-read-${item.id}`"
          @click="markRead(item)"
          >标为已读</el-button
        >
        <el-button
          v-if="linkOf(item)"
          type="primary"
          :data-testid="`open-${item.id}`"
          @click="open(item)"
          >{{ tab === 'todos' ? '处理待办' : '查看业务' }}</el-button
        >
      </div>
    </article>
    <el-pagination
      v-if="tab !== 'announcements' && total > 20"
      layout="prev, pager, next"
      :current-page="page"
      :page-size="20"
      :total="total"
      @current-change="load"
    />
  </section>
</template>
<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.inbox-item {
  padding: 16px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
h3 {
  margin: 0 0 8px;
}
.body {
  white-space: pre-wrap;
}
time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.item-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.el-pagination {
  margin-top: 16px;
}
</style>
