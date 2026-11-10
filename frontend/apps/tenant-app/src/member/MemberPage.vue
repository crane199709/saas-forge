<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey, useAccessControl } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, PageToolbar, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createOrganizationApi, type Department } from '../organization/org-api'
import { createRoleApi, type Role } from '../role/role-api'
import { createMemberApi, type Member, type MemberStatus } from './member-api'
import BulkInviteDialog from './BulkInviteDialog.vue'

type Action = 'suspend' | 'resume' | 'remove' | 'transferOwner'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createMemberApi(client)
const access = useAccessControl()
const statuses: MemberStatus[] = ['invited', 'active', 'suspended', 'left']
const size = 20
const members = ref<Member[]>([])
const page = ref(1)
const total = ref(0)
const keyword = ref('')
const status = ref<MemberStatus | ''>('')
const loaded = ref(false)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const pending = ref<{ action: Action; member: Member } | null>(null)

const inviting = ref(false)
const contact = ref('')
const roleIds = ref<string[]>([])
const departmentId = ref<string | null>(null)
const roles = ref<Role[]>([])
const departments = ref<Department[]>([])
const inviteError = ref<ErrorTip | null>(null)
const bulkInviting = ref(false)

async function load(number = page.value) {
  error.value = null
  try {
    const result = await api.members({
      page: number,
      size,
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined,
    })
    members.value = result.items
    page.value = result.page.number
    total.value = result.page.totalElements
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

function contactOf(member: Member) {
  return member.email ?? member.phone ?? ''
}

// Owner 只能经转让变更，不能停用或移除；已离开的成员只能重新邀请。
function actionsOf(member: Member): Action[] {
  if (member.owner) return []
  switch (member.status) {
    case 'invited':
      return ['remove']
    case 'active':
      return ['suspend', 'remove', 'transferOwner']
    case 'suspended':
      return ['resume', 'remove']
    default:
      return []
  }
}

async function confirm() {
  if (!pending.value) return
  const { action, member } = pending.value
  busy.value = true
  error.value = null
  try {
    await api[action](member.membershipId)
    pending.value = null
    await load()
  } catch (cause) {
    pending.value = null
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

// 预设角色与部门分别需要角色、组织的管理权限，没有时不加载也不展示对应选项。
async function openInvite() {
  contact.value = ''
  roleIds.value = []
  departmentId.value = null
  inviteError.value = null
  inviting.value = true
  try {
    const [roleList, departmentList] = await Promise.all([
      access.has('iam:role:manage') ? createRoleApi(client!).roles() : Promise.resolve([]),
      access.has('iam:org:manage')
        ? createOrganizationApi(client!).departments()
        : Promise.resolve([]),
    ])
    roles.value = roleList.filter((role) => !role.owner)
    departments.value = departmentList
  } catch (cause) {
    inviteError.value = errorTip(cause, t)
  }
}

async function invite() {
  busy.value = true
  inviteError.value = null
  try {
    await api.invite({
      contact: contact.value.trim(),
      roleIds: roleIds.value,
      primaryDepartmentId: departmentId.value,
    })
    inviting.value = false
    await load()
  } catch (cause) {
    inviteError.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

onMounted(() => void load(1))

const statusTag: Record<MemberStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  active: 'success',
  invited: 'warning',
  suspended: 'danger',
  left: 'info',
}
</script>

<template>
  <section>
    <PageHeader :title="t('member.title')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <template v-if="loaded">
      <PageToolbar>
        <el-input
          v-model="keyword"
          class="keyword"
          :placeholder="t('member.keyword')"
          clearable
          data-testid="member-keyword"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-select
          v-model="status"
          class="status-filter"
          :placeholder="t('member.allStatuses')"
          clearable
          data-testid="member-status-filter"
          @change="load(1)"
        >
          <el-option
            v-for="item in statuses"
            :key="item"
            :label="t(`member.status.${item}`)"
            :value="item"
          />
        </el-select>
        <el-button data-testid="member-search" @click="load(1)">{{ t('member.search') }}</el-button>
        <template #actions>
          <el-button
            v-if="access.has('iam:member:manage')"
            data-testid="member-bulk-invite"
            @click="bulkInviting = true"
            >{{ t('member.bulk.entry') }}</el-button
          >
          <el-button type="primary" data-testid="member-invite" @click="openInvite">{{
            t('member.invite')
          }}</el-button>
        </template>
      </PageToolbar>
      <el-table :data="members" row-key="membershipId">
        <el-table-column :label="t('member.name')" min-width="180">
          <template #default="{ row }">
            <span class="name" :data-testid="`member-${row.membershipId}`">
              <span>{{ row.displayName }}</span>
              <el-tag v-if="row.owner" size="small" type="info">{{ t('member.owner') }}</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('member.account')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.account ?? '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('member.contact')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ contactOf(row as Member) || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('member.statusLabel')" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag[row.status as MemberStatus]">
              {{ t(`member.status.${row.status}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-for="action in actionsOf(row as Member)"
              :key="action"
              link
              :type="action === 'remove' ? 'danger' : 'primary'"
              :data-testid="`member-${action}-${row.membershipId}`"
              @click="pending = { action, member: row as Member }"
            >
              {{ t(`member.actions.${action}`) }}
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
    </template>

    <el-dialog
      :model-value="pending !== null"
      :width="overlaySize.dialogSm"
      :title="pending ? t(`member.actions.${pending.action}`) : ''"
      @close="pending = null"
    >
      <p v-if="pending">
        {{ t(`member.confirm.${pending.action}`, { name: pending.member.displayName }) }}
      </p>
      <template #footer>
        <el-button @click="pending = null">{{ t('member.cancel') }}</el-button>
        <el-button type="primary" :disabled="busy" data-testid="member-confirm" @click="confirm">
          {{ t('member.ok') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inviting" :width="overlaySize.dialogSm" :title="t('member.invite')">
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('member.inviteContact')">
          <el-input v-model="contact" data-testid="member-invite-contact" />
        </el-form-item>
        <el-form-item v-if="roles.length" :label="t('member.inviteRoles')">
          <el-checkbox-group v-model="roleIds">
            <el-checkbox
              v-for="role in roles"
              :key="role.id"
              :value="role.id"
              :data-testid="`member-invite-role-${role.code}`"
            >
              {{ role.name }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="departments.length" :label="t('member.inviteDepartment')">
          <el-select v-model="departmentId" clearable data-testid="member-invite-department">
            <el-option
              v-for="department in departments"
              :key="department.id"
              :label="department.name"
              :value="department.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <ErrorAlert v-if="inviteError" :tip="inviteError" />
      <template #footer>
        <el-button @click="inviting = false">{{ t('member.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !contact.trim()"
          data-testid="member-invite-submit"
          @click="invite"
        >
          {{ t('member.inviteSubmit') }}
        </el-button>
      </template>
    </el-dialog>

    <BulkInviteDialog v-model="bulkInviting" @finished="load()" />
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.keyword {
  width: 280px;
}
.status-filter {
  width: 160px;
}
.name {
  display: inline-flex;
  gap: var(--forge-space-2);
  align-items: center;
}
.pagination {
  justify-content: flex-end;
  margin-top: var(--forge-space-4);
}
</style>
