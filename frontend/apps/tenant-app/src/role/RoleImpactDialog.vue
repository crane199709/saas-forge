<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@crane199709/ui'
import type { ImpactPreview, RoleDataPolicyImpact, RolePermissionImpact } from './role-api'

const props = defineProps<{
  roleName: string
  permissions: ImpactPreview<RolePermissionImpact> | null
  dataPolicies: ImpactPreview<RoleDataPolicyImpact> | null
  permissionNames: Map<string, string>
}>()
defineEmits<{ confirm: []; cancel: [] }>()
const { t } = useI18n()
const sections = computed(() => {
  const result = []
  if (props.permissions) {
    result.push({
      key: 'permissions',
      title: t('role.impact.permissions'),
      preview: props.permissions,
      summaryKey: 'role.impact.permissionSummary',
      lostPermissions: props.permissions.ok ? props.permissions.value.lostPermissions : undefined,
    })
  }
  if (props.dataPolicies) {
    result.push({
      key: 'dataPolicies',
      title: t('role.impact.dataPolicies'),
      notice: t('role.impact.dataPolicyNotice'),
      preview: props.dataPolicies,
      summaryKey: 'role.impact.dataPolicySummary',
    })
  }
  return result
})
const anyFailed = computed(
  () => props.permissions?.ok === false || props.dataPolicies?.ok === false,
)
</script>

<template>
  <el-dialog
    :model-value="true"
    append-to-body
    width="560px"
    :title="t('role.impact.title', { name: roleName })"
    @close="$emit('cancel')"
  >
    <div data-testid="role-impact">
      <section v-for="section in sections" :key="section.key">
        <h4>{{ section.title }}</h4>
        <p v-if="section.notice">{{ section.notice }}</p>
        <template v-if="section.preview.ok">
          <p>{{ t(section.summaryKey, { count: section.preview.value.affectedCount }) }}</p>
          <template v-if="section.lostPermissions">
            <p class="label">{{ t('role.impact.lostPermissions') }}</p>
            <ul class="list">
              <li v-for="code in section.lostPermissions" :key="code">
                {{ permissionNames.get(code) ?? code }}
              </li>
            </ul>
            <p class="label">{{ t('role.impact.affectedMembers') }}</p>
          </template>
          <ul class="list">
            <li v-for="member in section.preview.value.members" :key="member.membershipId">
              {{ member.displayName }}（{{ member.account }}）
            </li>
          </ul>
          <p
            v-if="section.preview.value.affectedCount > section.preview.value.members.length"
            class="truncated"
          >
            {{
              t('role.impact.membersTruncated', {
                shown: section.preview.value.members.length,
                total: section.preview.value.affectedCount,
              })
            }}
          </p>
        </template>
        <template v-else>
          <p>{{ t('role.impact.previewFailed') }}</p>
          <ErrorAlert :tip="section.preview.tip" />
        </template>
      </section>
    </div>
    <template #footer>
      <el-button data-testid="role-impact-cancel" @click="$emit('cancel')">
        {{ t('role.dialog.cancel') }}
      </el-button>
      <el-button type="primary" data-testid="role-impact-confirm" @click="$emit('confirm')">
        {{ anyFailed ? t('role.impact.continue') : t('role.impact.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.list {
  max-height: 160px;
  margin: 0 0 8px;
  padding-left: 20px;
  overflow-y: auto;
}
.label,
.truncated {
  color: var(--el-text-color-secondary);
}
</style>
