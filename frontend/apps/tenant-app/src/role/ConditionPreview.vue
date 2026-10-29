<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorAlert from '../ErrorAlert.vue'
import { errorTip, type ErrorTip } from '../error-tip'
import type {
  ConditionAttribute,
  ConditionOptions,
  DataPolicy,
  MemberRoles,
  PolicyPreview,
  RoleApi,
} from './role-api'

// 预览不保存：以所选成员当前的主体属性，对手填的样例数据逐条求值，与业务服务的单对象校验同一个求值器。
const props = defineProps<{
  api: RoleApi
  policy: DataPolicy
  attributes: ConditionAttribute[]
  options: ConditionOptions
}>()
const { t } = useI18n()

type Cell = string | number | boolean | null
const members = ref<MemberRoles[]>([])
const membershipId = ref('')
const samples = ref<Record<string, Cell>[]>([{}])
const result = ref<PolicyPreview | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

const code = (attribute: ConditionAttribute) => attribute.name.slice('resource.'.length)

onMounted(async () => {
  try {
    // 预览只需挑一个成员，取第一页足够
    members.value = (await props.api.members(1, 100)).items
    membershipId.value = members.value[0]?.membershipId ?? ''
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
})

// 空单元格视为属性为空，不提交
const cleaned = () =>
  samples.value.map((row) =>
    Object.fromEntries(Object.entries(row).filter(([, value]) => value !== null && value !== '')),
  )

async function run() {
  busy.value = true
  error.value = null
  result.value = null
  try {
    result.value = await props.api.preview(props.policy, membershipId.value, cleaned())
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

const contextLabel = (name: string) => {
  const attribute = [...props.options.subject, ...props.options.environment].find(
    (item) => item.name === name,
  )
  return attribute?.label ?? t(`role.condition.attr.${name}`)
}
const show = (value: unknown) => (Array.isArray(value) ? value.join('、') : String(value))
</script>

<template>
  <div class="preview" data-testid="condition-preview">
    <div class="head">
      <span>{{ t('role.preview.member') }}</span>
      <el-select v-model="membershipId" filterable class="member" data-testid="preview-member">
        <el-option
          v-for="member in members"
          :key="member.membershipId"
          :label="`${member.displayName}（${member.account}）`"
          :value="member.membershipId"
        />
      </el-select>
      <el-button size="small" @click="samples.push({})">{{
        t('role.preview.addSample')
      }}</el-button>
      <el-button
        size="small"
        type="primary"
        :disabled="busy || !membershipId"
        data-testid="preview-run"
        @click="run"
      >
        {{ t('role.preview.run') }}
      </el-button>
    </div>
    <table class="samples">
      <thead>
        <tr>
          <th v-for="attribute in attributes" :key="attribute.name">{{ attribute.label }}</th>
          <th>{{ t('role.preview.result') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in samples" :key="index" data-testid="preview-sample">
          <td v-for="attribute in attributes" :key="attribute.name">
            <el-select
              v-if="attribute.type === 'ltree'"
              v-model="row[code(attribute)]"
              clearable
              filterable
              size="small"
            >
              <el-option
                v-for="choice in options.departments"
                :key="choice.value"
                :label="choice.label"
                :value="choice.value"
              />
            </el-select>
            <el-input-number
              v-else-if="attribute.type === 'number'"
              v-model="row[code(attribute) as string] as number | undefined"
              :value-on-clear="null"
              size="small"
              controls-position="right"
              :data-testid="`preview-${code(attribute)}-${index}`"
            />
            <el-select
              v-else-if="attribute.type === 'boolean'"
              v-model="row[code(attribute)]"
              clearable
              size="small"
            >
              <el-option :value="true" :label="t('role.condition.true')" />
              <el-option :value="false" :label="t('role.condition.false')" />
            </el-select>
            <el-input
              v-else
              v-model="row[code(attribute) as string] as string"
              size="small"
              clearable
            />
          </td>
          <td :data-testid="`preview-result-${index}`">
            <template v-if="result">
              <el-tag v-if="result.matches[index]" type="success" size="small">
                {{ t('role.preview.hit') }}
              </el-tag>
              <el-tag v-else type="info" size="small">{{ t('role.preview.miss') }}</el-tag>
            </template>
          </td>
          <td>
            <el-button
              link
              type="danger"
              :disabled="samples.length === 1"
              @click="samples.splice(index, 1)"
            >
              {{ t('role.condition.remove') }}
            </el-button>
          </td>
        </tr>
      </tbody>
    </table>
    <ErrorAlert v-if="error" :tip="error" />
    <dl v-if="result" class="context" data-testid="preview-context">
      <template v-for="(value, name) in result.context" :key="name">
        <dt>{{ contextLabel(String(name)) }}</dt>
        <dd>{{ show(value) }}</dd>
      </template>
    </dl>
  </div>
</template>

<style scoped>
.preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.head {
  display: flex;
  gap: 8px;
  align-items: center;
}
.member {
  width: 200px;
}
.samples {
  border-collapse: collapse;
}
.samples th,
.samples td {
  padding: 2px 4px;
  text-align: left;
}
.context {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 2px 12px;
  margin: 0;
  color: var(--el-text-color-secondary);
}
.context dd {
  margin: 0;
  word-break: break-all;
}
</style>
