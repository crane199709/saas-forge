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
      <section v-if="permissions">
        <h4>{{ t('role.impact.permissions') }}</h4>
        <template v-if="permissions.ok">
          <p>
            {{ t('role.impact.permissionSummary', { count: permissions.value.affectedCount }) }}
          </p>
          <p class="label">{{ t('role.impact.lostPermissions') }}</p>
          <ul class="list">
            <li v-for="code in permissions.value.lostPermissions" :key="code">
              {{ permissionNames.get(code) ?? code }}
            </li>
          </ul>
          <p class="label">{{ t('role.impact.affectedMembers') }}</p>
          <ul class="list">
            <li v-for="member in permissions.value.members" :key="member.membershipId">
              {{ member.displayName }}（{{ member.account }}）
            </li>
          </ul>
          <p
            v-if="permissions.value.affectedCount > permissions.value.members.length"
            class="truncated"
          >
            {{
              t('role.impact.membersTruncated', {
                shown: permissions.value.members.length,
                total: permissions.value.affectedCount,
              })
            }}
          </p>
        </template>
        <template v-else>
          <p>{{ t('role.impact.previewFailed') }}</p>
          <ErrorAlert :tip="permissions.tip" />
        </template>
      </section>
      <section v-if="dataPolicies">
        <h4>{{ t('role.impact.dataPolicies') }}</h4>
        <p>{{ t('role.impact.dataPolicyNotice') }}</p>
        <template v-if="dataPolicies.ok">
          <p>
            {{ t('role.impact.dataPolicySummary', { count: dataPolicies.value.affectedCount }) }}
          </p>
          <ul class="list">
            <li v-for="member in dataPolicies.value.members" :key="member.membershipId">
              {{ member.displayName }}（{{ member.account }}）
            </li>
          </ul>
          <p
            v-if="dataPolicies.value.affectedCount > dataPolicies.value.members.length"
            class="truncated"
          >
            {{
              t('role.impact.membersTruncated', {
                shown: dataPolicies.value.members.length,
                total: dataPolicies.value.affectedCount,
              })
            }}
          </p>
        </template>
        <template v-else>
          <p>{{ t('role.impact.previewFailed') }}</p>
          <ErrorAlert :tip="dataPolicies.tip" />
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
