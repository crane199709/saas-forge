<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import type { PlanImpactPreview } from './tenant-api'

defineProps<{ planName: string; preview: PlanImpactPreview }>()
defineEmits<{ confirm: []; cancel: [] }>()
const { t } = useI18n()
</script>

<template>
  <el-dialog
    :model-value="true"
    append-to-body
    :width="overlaySize.dialogMd"
    :title="t('tenant.planImpact.title', { name: planName })"
    @close="$emit('cancel')"
  >
    <div data-testid="plan-impact">
      <template v-if="preview.ok">
        <p>{{ t('tenant.planImpact.affected', { count: preview.value.affectedMemberships }) }}</p>
        <p v-if="preview.value.membersOverQuota > 0" class="warning">
          {{
            t('tenant.planImpact.overQuota', {
              count: preview.value.memberCount ?? 0,
              quota: preview.value.memberQuota ?? 0,
              over: preview.value.membersOverQuota,
            })
          }}
        </p>
        <template v-if="preview.value.lostPermissions.length > 0">
          <h4>{{ t('tenant.planImpact.lostPermissions') }}</h4>
          <ul class="list">
            <li v-for="code in preview.value.lostPermissions" :key="code">{{ code }}</li>
          </ul>
        </template>
        <template v-if="preview.value.lostSubApps.length > 0">
          <h4>{{ t('tenant.planImpact.lostSubApps') }}</h4>
          <ul class="list">
            <li v-for="code in preview.value.lostSubApps" :key="code">{{ code }}</li>
          </ul>
        </template>
      </template>
      <template v-else>
        <p>{{ t('tenant.planImpact.previewFailed') }}</p>
        <ErrorAlert :tip="preview.tip" />
      </template>
    </div>
    <template #footer>
      <el-button data-testid="plan-impact-cancel" @click="$emit('cancel')">
        {{ t('tenant.planImpact.cancel') }}
      </el-button>
      <el-button type="primary" data-testid="plan-impact-confirm" @click="$emit('confirm')">
        {{ preview.ok ? t('tenant.planImpact.confirm') : t('tenant.planImpact.continue') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.warning {
  color: var(--el-color-warning);
}
h4 {
  margin: var(--forge-space-3) 0 var(--forge-space-1);
}
.list {
  max-height: 160px;
  margin: 0 0 var(--forge-space-2);
  padding-left: var(--forge-space-5);
  overflow-y: auto;
}
</style>
