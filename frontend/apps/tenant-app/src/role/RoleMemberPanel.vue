<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type { MemberRoles, Role, RoleApi } from './role-api'

const props = defineProps<{ api: RoleApi; roles: Role[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const size = 20
const members = ref<MemberRoles[]>([])
const page = ref(1)
const total = ref(0)
const editing = ref<MemberRoles | null>(null)
const draft = ref<string[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

const roleNames = computed(() => new Map(props.roles.map((role) => [role.id, role.name])))
const ownerIds = computed(() => new Set(props.roles.filter((r) => r.owner).map((r) => r.id)))
// Owner 只能经转让变更，不出现在可选项中，保存时也不提交。
const assignable = computed(() => props.roles.filter((role) => !role.owner))

async function load(number = page.value) {
  error.value = null
  try {
    const result = await props.api.members(number, size)
    members.value = result.items
    page.value = result.page.number
    total.value = result.page.totalElements
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

function edit(member: MemberRoles) {
  editing.value = member
  draft.value = member.roleIds.filter((id) => !ownerIds.value.has(id))
}

async function save() {
  if (!editing.value) return
  busy.value = true
  error.value = null
  try {
    await props.api.assign(editing.value.membershipId, draft.value)
    editing.value = null
    await load()
    // 角色的持有人数随之变化。
    emit('changed')
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

onMounted(() => void load(1))
</script>

<template>
  <div>
    <ErrorAlert v-if="error" class="list-error" :tip="error" />
    <el-table :data="members" row-key="membershipId">
      <el-table-column prop="displayName" :label="t('role.member.name')" min-width="160" />
      <el-table-column
        prop="account"
        :label="t('role.member.account')"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column :label="t('role.member.status')" width="96">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">
            {{ t(`member.status.${row.status}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('role.member.roles')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span :data-testid="`role-member-roles-${row.membershipId}`">{{
            row.roleIds.map((id: string) => roleNames.get(id) ?? id).join('、') || '—'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="96" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :data-testid="`role-member-edit-${row.membershipId}`"
            @click="edit(row as MemberRoles)"
          >
            {{ t('role.member.edit') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="total > size"
      class="pagination"
      layout="total, prev, pager, next"
      :current-page="page"
      :page-size="size"
      :total="total"
      @current-change="load"
    />
    <el-dialog
      :model-value="editing !== null"
      :width="overlaySize.dialogSm"
      :title="t('role.member.dialog', { name: editing?.displayName ?? '' })"
      @close="editing = null"
    >
      <el-checkbox-group v-model="draft">
        <el-checkbox
          v-for="role in assignable"
          :key="role.id"
          :value="role.id"
          :data-testid="`role-option-${role.code}`"
        >
          {{ role.name }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="editing = null">{{ t('role.member.cancel') }}</el-button>
        <el-button type="primary" :disabled="busy" data-testid="role-member-save" @click="save">
          {{ t('role.member.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.list-error {
  margin-bottom: var(--forge-space-3);
}
.pagination {
  justify-content: flex-end;
  margin-top: var(--forge-space-4);
}
</style>
