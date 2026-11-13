<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useTenantClient, useAccessControl } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, PageToolbar, formatDateTime } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createNotificationApi, type InboxItem, type InboxTab } from './notification-api'
import { useInboxUnread } from './unread-count'
const client = useTenantClient()
const api = createNotificationApi(client)
const access = useAccessControl()
const route = useRoute()
let permissionsReady = true
const unread = useInboxUnread()
const router = useRouter()
const { t } = useI18n()
const tabs = { notifications: '通知', announcements: '公告', todos: '待办' }
const codes: Record<InboxTab, string> = {
  notifications: 'notification:read',
  announcements: 'announcement:read',
  todos: 'todo:read',
}
const allowedTabs = computed(() =>
  (Object.keys(tabs) as InboxTab[]).filter((kind) => access.has(codes[kind])),
)
function requestedTab() {
  const requested = route.query.tab
  return (
    allowedTabs.value.find((kind) => kind === requested) ?? allowedTabs.value[0] ?? 'notifications'
  )
}
const tab = ref<InboxTab>(requestedTab())
const items = ref<InboxItem[]>([])
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
// 同一时间只展开一条，翻页、刷新后仍按 id 保持。
const expanded = ref<string | null>(typeof route.query.item === 'string' ? route.query.item : null)
let generation = 0
let unsubscribe: (() => void) | undefined
async function load(number = page.value) {
  if (!permissionsReady || !access.has(codes[tab.value])) return
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
  if (!access.has(codes[kind])) return
  tab.value = kind
  total.value = 0
  expanded.value = null
  void load(1)
}
async function markRead(item?: InboxItem) {
  if (tab.value === 'todos' || !access.has(codes[tab.value])) return
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
function toggle(item: InboxItem) {
  expanded.value = expanded.value === item.id ? null : item.id
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
watch(allowedTabs, () => {
  permissionsReady = true
  generation += 1
  items.value = []
  total.value = 0
  error.value = null
  busy.value = false
  tab.value = requestedTab()
  expanded.value = typeof route.query.item === 'string' ? route.query.item : null
  void load(1)
})
watch(
  () => [route.query.tab, route.query.item],
  () => {
    tab.value = requestedTab()
    expanded.value = typeof route.query.item === 'string' ? route.query.item : null
    void load(1)
  },
)
onMounted(() => {
  let membership = client.session()?.membershipId
  unsubscribe = client.onSessionChange((session) => {
    if (membership === session?.membershipId) return
    membership = session?.membershipId
    permissionsReady = false
    expanded.value = null
    generation += 1
    items.value = []
    total.value = 0
    error.value = null
    busy.value = false
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
    <PageHeader title="通知中心" />
    <PageToolbar v-if="allowedTabs.length">
      <el-button-group role="tablist" aria-label="通知类型">
        <el-button
          v-for="kind in allowedTabs"
          :key="kind"
          plain
          role="tab"
          :aria-selected="tab === kind"
          :type="tab === kind ? 'primary' : 'default'"
          :data-testid="`tab-${kind}`"
          @click="select(kind)"
          >{{ tabs[kind] }}</el-button
        >
      </el-button-group>
      <template #actions>
        <el-button :disabled="busy" @click="load()">刷新列表</el-button>
        <el-button
          v-if="tab === 'notifications'"
          :disabled="busy || !items.length"
          @click="markRead()"
          >全部通知标为已读</el-button
        >
      </template>
    </PageToolbar>
    <p v-if="!allowedTabs.length" class="placeholder">暂无可查看的消息</p>
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <p v-if="busy" class="placeholder" role="status">加载中…</p>
    <p v-else-if="allowedTabs.length && !error && !items.length" class="placeholder">
      暂无{{ tabs[tab] }}
    </p>
    <article
      v-for="item in items"
      :key="item.id"
      class="inbox-item"
      :class="{ expanded: expanded === item.id }"
      :data-testid="`inbox-${item.id}`"
      @click="toggle(item)"
    >
      <span
        class="dot"
        :class="{ unread: tab !== 'todos' && !item.read }"
        aria-hidden="true"
      ></span>
      <div class="content">
        <h3 :class="{ unread: tab !== 'todos' && !item.read }">
          <button type="button" class="title" :aria-expanded="expanded === item.id">
            {{ item.title }}
          </button>
          <span v-if="tab !== 'todos'" class="visually-hidden">{{
            item.read ? '已读' : '未读'
          }}</span>
        </h3>
        <p class="body">{{ item.body }}</p>
      </div>
      <time>{{ formatDateTime(item.occurredAt ?? item.publishedAt) }}</time>
      <div class="item-actions" @click.stop>
        <el-button
          v-if="tab !== 'todos' && !item.read"
          link
          :disabled="busy"
          :data-testid="`mark-read-${item.id}`"
          @click="markRead(item)"
          >标为已读</el-button
        >
        <el-button
          v-if="linkOf(item)"
          link
          type="primary"
          :data-testid="`open-${item.id}`"
          @click="open(item)"
          >{{ tab === 'todos' ? '处理待办' : '查看业务' }}</el-button
        >
      </div>
    </article>
    <el-pagination
      v-if="tab !== 'announcements' && total > 20"
      class="pagination"
      layout="total, prev, pager, next"
      :current-page="page"
      :page-size="20"
      :total="total"
      @current-change="load"
    />
  </section>
</template>
<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.placeholder {
  margin: 0;
  padding: var(--forge-space-5) 0;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.inbox-item {
  display: flex;
  gap: var(--forge-space-3);
  align-items: center;
  padding: var(--forge-space-3) var(--forge-space-2);
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.inbox-item:hover {
  background-color: var(--el-fill-color-lighter);
}
.inbox-item.expanded {
  align-items: flex-start;
}
.dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: var(--forge-radius-sm);
}
.dot.unread {
  background-color: var(--el-color-danger);
}
.content {
  display: flex;
  flex: 1;
  gap: var(--forge-space-3);
  align-items: baseline;
  min-width: 0;
}
.expanded .content {
  flex-direction: column;
  gap: var(--forge-space-2);
}
h3 {
  flex: none;
  max-width: 45%;
  margin: 0;
  overflow: hidden;
  font-size: var(--forge-font-size-sm);
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}
h3.unread {
  font-weight: 600;
}
.expanded h3 {
  max-width: 100%;
  white-space: normal;
}
.title {
  display: block;
  max-width: 100%;
  overflow: hidden;
  padding: 0;
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
  text-overflow: ellipsis;
  background: none;
  border: 0;
}
.expanded .title {
  white-space: normal;
}
.body {
  flex: 1;
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.expanded .body {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
time {
  flex: none;
  color: var(--el-text-color-secondary);
}
.item-actions {
  display: flex;
  flex: none;
  justify-content: flex-end;
  width: 160px;
  cursor: default;
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 640px) {
  .inbox-item {
    flex-wrap: wrap;
  }
  .content {
    flex-basis: calc(100% - var(--forge-space-5));
    flex-direction: column;
    gap: var(--forge-space-1);
  }
  h3 {
    max-width: 100%;
  }
  .body {
    width: 100%;
  }
  .item-actions {
    width: auto;
    margin-left: auto;
  }
}
.pagination {
  justify-content: flex-end;
  margin-top: var(--forge-space-4);
}
</style>
