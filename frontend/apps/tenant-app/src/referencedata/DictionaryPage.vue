<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey, useAccessControl } from '@crane199709/sdk'
import { ErrorAlert, PageHeader } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import DictionaryPanel from './DictionaryPanel.vue'
import DictionaryHistoryPanel from './DictionaryHistoryPanel.vue'
import { createReferenceDataApi, type Dictionary } from './reference-data-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createReferenceDataApi(client)
const access = useAccessControl()
const canManage = computed(() => access.has('iam:reference-data:manage'))
const canReadHistory = computed(() => access.has('iam:dictionary-history:read'))
const tab = ref(canManage.value ? 'items' : 'history')
const dictionaries = ref<Dictionary[]>([])
const selectedKey = ref('')
const loaded = ref(false)
const error = ref<ErrorTip | null>(null)

const keyOf = (dictionary: Dictionary) => `${dictionary.service}/${dictionary.code}`

const services = computed(() => {
  const groups = new Map<string, Dictionary[]>()
  for (const dictionary of dictionaries.value) {
    groups.set(dictionary.service, [...(groups.get(dictionary.service) ?? []), dictionary])
  }
  return [...groups].map(([service, list]) => ({ service, dictionaries: list }))
})

const selected = computed(() =>
  dictionaries.value.find((dictionary) => keyOf(dictionary) === selectedKey.value),
)

// 每次修改后整体重读：排序、状态与名称校验都以服务端合并结果为准。
async function load() {
  error.value = null
  try {
    dictionaries.value = await api.dictionaries()
    if (!selected.value)
      selectedKey.value = dictionaries.value[0] ? keyOf(dictionaries.value[0]) : ''
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

onMounted(load)
</script>

<template>
  <section>
    <PageHeader :title="t('referenceData.dictionary.title')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <p v-if="loaded && !dictionaries.length" class="hint">
      {{ t('referenceData.dictionary.empty') }}
    </p>
    <div v-if="loaded && dictionaries.length" class="layout">
      <nav>
        <div
          v-for="group in services"
          :key="group.service"
          class="group"
          :data-testid="`service-${group.service}`"
        >
          <h3>{{ group.service }}</h3>
          <el-button
            v-for="dictionary in group.dictionaries"
            :key="dictionary.code"
            link
            :type="keyOf(dictionary) === selectedKey ? 'primary' : 'default'"
            :data-testid="`dictionary-${keyOf(dictionary)}`"
            @click="selectedKey = keyOf(dictionary)"
          >
            {{ dictionary.name }}
          </el-button>
        </div>
      </nav>
      <div v-if="selected" class="content">
        <el-tabs v-model="tab">
          <el-tab-pane :label="t('referenceData.history.items')" name="items" />
          <el-tab-pane
            v-if="canReadHistory"
            :label="t('referenceData.history.title')"
            name="history"
          />
        </el-tabs>
        <DictionaryHistoryPanel
          v-if="tab === 'history' && canReadHistory"
          :key="selectedKey"
          :api="api"
          :dictionary="selected"
        />
        <DictionaryPanel
          v-else
          :api="api"
          :dictionary="selected"
          :can-manage="canManage"
          @changed="load"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.hint {
  color: var(--el-text-color-secondary);
}
.layout {
  display: flex;
  gap: var(--forge-space-5);
}
.content {
  flex: 1;
  min-width: 0;
}
nav {
  flex-shrink: 0;
  width: 180px;
  padding-right: var(--forge-space-4);
  border-right: 1px solid var(--el-border-color-lighter);
}
.group + .group {
  margin-top: var(--forge-space-4);
}
nav h3 {
  margin: 0 0 var(--forge-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
  font-weight: 400;
}
nav .el-button {
  display: block;
  margin: var(--forge-space-2) 0 0;
}
@media (max-width: 1100px) {
  .layout {
    flex-direction: column;
    gap: var(--forge-space-3);
  }
  nav {
    display: flex;
    flex-wrap: wrap;
    gap: var(--forge-space-4);
    width: auto;
    padding-right: 0;
    padding-bottom: var(--forge-space-3);
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
  .group + .group {
    margin-top: 0;
  }
}
</style>
