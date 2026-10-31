<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type {
  Assignment,
  AttributeValue,
  Department,
  ExtendedAttribute,
  MemberAssignment,
  OrganizationApi,
  Position,
} from './org-api'

const props = defineProps<{
  api: OrganizationApi
  departments: Department[]
  positions: Position[]
  attributes: ExtendedAttribute[]
}>()
const { t } = useI18n()
const size = 20
const members = ref<MemberAssignment[]>([])
const page = ref(1)
const total = ref(0)
const editing = ref<MemberAssignment | null>(null)
const draft = ref<Assignment>({
  primaryDepartmentId: null,
  departmentIds: [],
  positionIds: [],
  attributes: {},
})
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

const departmentNames = computed(
  () => new Map(props.departments.map((item) => [item.id, item.name])),
)
const positionNames = computed(() => new Map(props.positions.map((item) => [item.id, item.name])))
const names = (ids: string[], source: Map<string, string>) =>
  ids.map((id) => source.get(id) ?? id).join('、')

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

function edit(member: MemberAssignment) {
  editing.value = member
  draft.value = {
    primaryDepartmentId: member.primaryDepartmentId,
    departmentIds: [...member.departmentIds],
    positionIds: [...member.positionIds],
    attributes: { ...member.attributes },
  }
}

function setAttribute(code: string, value: AttributeValue | null | undefined) {
  const next = { ...draft.value.attributes }
  // 清空的输入不提交，服务端按未填写处理（必填项会被拒绝）。
  if (value === null || value === undefined || value === '') delete next[code]
  else next[code] = value
  draft.value.attributes = next
}

async function save() {
  if (!editing.value) return
  busy.value = true
  error.value = null
  try {
    const primary = draft.value.primaryDepartmentId || null
    await props.api.assign(editing.value.membershipId, {
      ...draft.value,
      primaryDepartmentId: primary,
      departmentIds: draft.value.departmentIds.filter((id) => id !== primary),
    })
    editing.value = null
    await load()
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
    <table class="members">
      <thead>
        <tr>
          <th>{{ t('org.member.name') }}</th>
          <th>{{ t('org.member.account') }}</th>
          <th>{{ t('org.member.status') }}</th>
          <th>{{ t('org.member.primary') }}</th>
          <th>{{ t('org.member.concurrent') }}</th>
          <th>{{ t('org.member.positions') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="member in members"
          :key="member.membershipId"
          :data-testid="`member-${member.membershipId}`"
        >
          <td>{{ member.displayName }}</td>
          <td>{{ member.account }}</td>
          <td>{{ member.status }}</td>
          <td>
            {{
              member.primaryDepartmentId ? names([member.primaryDepartmentId], departmentNames) : ''
            }}
          </td>
          <td>{{ names(member.departmentIds, departmentNames) }}</td>
          <td>{{ names(member.positionIds, positionNames) }}</td>
          <td>
            <el-button
              link
              type="primary"
              :data-testid="`member-edit-${member.membershipId}`"
              @click="edit(member)"
            >
              {{ t('org.member.edit') }}
            </el-button>
          </td>
        </tr>
      </tbody>
    </table>
    <el-pagination
      v-if="total > size"
      layout="prev, pager, next"
      :current-page="page"
      :page-size="size"
      :total="total"
      @current-change="load"
    />
    <ErrorAlert v-if="error" :tip="error" />
    <el-dialog
      :model-value="editing !== null"
      :title="t('org.member.dialog', { name: editing?.displayName ?? '' })"
      @close="editing = null"
    >
      <el-form label-width="96px">
        <el-form-item :label="t('org.member.primary')">
          <el-select v-model="draft.primaryDepartmentId" clearable data-testid="assignment-primary">
            <el-option
              v-for="item in departments"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('org.member.concurrent')">
          <el-select v-model="draft.departmentIds" multiple data-testid="assignment-departments">
            <el-option
              v-for="item in departments.filter((d) => d.id !== draft.primaryDepartmentId)"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('org.member.positions')">
          <el-select v-model="draft.positionIds" multiple data-testid="assignment-positions">
            <el-option
              v-for="item in positions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-for="attribute in attributes"
          :key="attribute.id"
          :label="attribute.name"
          :required="attribute.required"
        >
          <el-switch
            v-if="attribute.type === 'boolean'"
            :model-value="draft.attributes[attribute.code] === true"
            @update:model-value="
              (value: string | number | boolean) => setAttribute(attribute.code, value)
            "
          />
          <el-input-number
            v-else-if="attribute.type === 'number'"
            :model-value="draft.attributes[attribute.code] as number | undefined"
            @update:model-value="
              (value: number | undefined | null) => setAttribute(attribute.code, value)
            "
          />
          <el-input
            v-else
            :model-value="(draft.attributes[attribute.code] as string | undefined) ?? ''"
            maxlength="500"
            @update:model-value="(value: string) => setAttribute(attribute.code, value)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editing = null">{{ t('org.member.cancel') }}</el-button>
        <el-button type="primary" :disabled="busy" data-testid="assignment-save" @click="save">
          {{ t('org.member.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.members {
  width: 100%;
  border-collapse: collapse;
}
.members th,
.members td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
</style>
