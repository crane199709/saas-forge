<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  adjustRule,
  emptyGroup,
  emptyRule,
  operatorsFor,
  referencesFor,
  shapeOf,
  type GroupNode,
  type Operator,
  type RuleNode,
  type Scalar,
} from './condition'
import type { ConditionAttribute, ConditionOptions, ValueOption } from './role-api'

// 条件树由 RoleListPanel 的草稿持有，各层直接修改同一棵树，保存时整体转换。
const props = defineProps<{
  node: GroupNode
  resourceAttributes: ConditionAttribute[]
  options: ConditionOptions
  root?: boolean
}>()
const emit = defineEmits<{ remove: [] }>()
const { t } = useI18n()

const all = computed(() => [
  ...props.resourceAttributes,
  ...props.options.subject,
  ...props.options.environment,
])
const byName = computed(() => new Map(all.value.map((item) => [item.name, item])))
const attributeGroups = computed(() => [
  { key: 'resource', attributes: props.resourceAttributes },
  { key: 'subject', attributes: props.options.subject },
  { key: 'environment', attributes: props.options.environment },
])

const label = (attribute: ConditionAttribute) =>
  attribute.label ?? t(`role.condition.attr.${attribute.name}`)

function changeRule(index: number, patch: Partial<RuleNode>) {
  const rule = { ...(props.node.children[index] as RuleNode), ...patch }
  props.node.children[index] = adjustRule(rule, byName.value.get(rule.attr))
}

function setMode(index: number, rule: RuleNode, mode: 'literal' | 'ref') {
  const list = shapeOf(rule.op as Operator) === 'list'
  props.node.children[index] = {
    ...rule,
    value: mode === 'ref' ? { kind: 'ref', ref: '' } : { kind: 'literal', value: list ? [] : null },
  }
}

const refs = (rule: RuleNode) => {
  const attribute = byName.value.get(rule.attr)
  return attribute && rule.op ? referencesFor(attribute, rule.op, all.value) : []
}

const control = (rule: RuleNode) => {
  const attribute = byName.value.get(rule.attr)!
  const shape = shapeOf(rule.op as Operator)
  const multiple = shape !== 'scalar'
  let choices: ValueOption[] | null = null
  if (attribute.type === 'ltree') choices = props.options.departments
  else if (attribute.name === 'subject.positionIds') choices = props.options.positions
  else if (attribute.type === 'boolean')
    choices = [
      { value: 'true', label: t('role.condition.true') },
      { value: 'false', label: t('role.condition.false') },
    ]
  return { type: attribute.type, multiple, choices }
}

// 下拉选项的值都是字符串，布尔与数值在写回时还原类型。
function toScalar(type: string, raw: string | number): Scalar {
  if (type === 'boolean') return raw === 'true'
  if (type === 'number') return typeof raw === 'number' ? raw : Number(raw)
  return String(raw)
}

function setLiteral(rule: RuleNode, raw: unknown) {
  const type = byName.value.get(rule.attr)!.type
  const value = Array.isArray(raw)
    ? raw
        .map((item) => toScalar(type, item as string | number))
        .filter((item) => !(typeof item === 'number' && Number.isNaN(item)))
    : raw === null || raw === undefined || raw === ''
      ? null
      : toScalar(type, raw as string | number)
  rule.value = { kind: 'literal', value }
}

const literalOf = (rule: RuleNode) => {
  if (rule.value.kind !== 'literal') return null
  const value = rule.value.value
  // 下拉框按字符串匹配选项
  if (typeof value === 'boolean') return String(value)
  return Array.isArray(value)
    ? value.map((item) => (typeof item === 'boolean' ? String(item) : item))
    : value
}
</script>

<template>
  <div class="group" :class="{ nested: !root }" data-testid="condition-group">
    <div class="group-head">
      <el-radio-group v-model="node.logic" size="small">
        <el-radio-button value="and" data-testid="condition-logic-and">
          {{ t('role.condition.and') }}
        </el-radio-button>
        <el-radio-button value="or" data-testid="condition-logic-or">
          {{ t('role.condition.or') }}
        </el-radio-button>
      </el-radio-group>
      <el-checkbox v-model="node.negate" size="small" data-testid="condition-negate">
        {{ t('role.condition.negate') }}
      </el-checkbox>
      <el-button
        size="small"
        data-testid="condition-add-rule"
        @click="node.children.push(emptyRule())"
      >
        {{ t('role.condition.addRule') }}
      </el-button>
      <el-button
        size="small"
        data-testid="condition-add-group"
        @click="node.children.push(emptyGroup())"
      >
        {{ t('role.condition.addGroup') }}
      </el-button>
      <el-button v-if="!root" size="small" link type="danger" @click="emit('remove')">
        {{ t('role.condition.removeGroup') }}
      </el-button>
    </div>
    <template v-for="(child, index) in node.children" :key="index">
      <ConditionGroup
        v-if="child.kind === 'group'"
        :node="child"
        :resource-attributes="resourceAttributes"
        :options="options"
        @remove="node.children.splice(index, 1)"
      />
      <div v-else class="rule" data-testid="condition-rule">
        <el-select
          :model-value="child.attr"
          :placeholder="t('role.condition.attribute')"
          filterable
          class="attr"
          data-testid="condition-attr"
          @update:model-value="(attr: string) => changeRule(index, { attr })"
        >
          <el-option-group
            v-for="group in attributeGroups"
            :key="group.key"
            :label="t(`role.condition.groups.${group.key}`)"
          >
            <el-option
              v-for="attribute in group.attributes"
              :key="attribute.name"
              :label="label(attribute)"
              :value="attribute.name"
            />
          </el-option-group>
        </el-select>
        <el-select
          :model-value="child.op"
          :disabled="!byName.get(child.attr)"
          :placeholder="t('role.condition.operator')"
          class="op"
          data-testid="condition-op"
          @update:model-value="(op: Operator) => changeRule(index, { op })"
        >
          <el-option
            v-for="op in byName.get(child.attr) ? operatorsFor(byName.get(child.attr)!) : []"
            :key="op"
            :label="t(`role.condition.op.${op}`)"
            :value="op"
          />
        </el-select>
        <template v-if="child.op && shapeOf(child.op) !== 'none'">
          <el-select
            v-if="refs(child).length > 0"
            :model-value="child.value.kind"
            class="mode"
            data-testid="condition-mode"
            @update:model-value="(mode: 'literal' | 'ref') => setMode(index, child, mode)"
          >
            <el-option value="literal" :label="t('role.condition.literal')" />
            <el-option value="ref" :label="t('role.condition.reference')" />
          </el-select>
          <el-select
            v-if="child.value.kind === 'ref'"
            v-model="child.value.ref"
            class="value"
            data-testid="condition-ref"
          >
            <el-option
              v-for="attribute in refs(child)"
              :key="attribute.name"
              :label="label(attribute)"
              :value="attribute.name"
            />
          </el-select>
          <el-select
            v-else-if="control(child).choices"
            :model-value="literalOf(child)"
            :multiple="control(child).multiple"
            filterable
            class="value"
            data-testid="condition-value"
            @update:model-value="(raw: unknown) => setLiteral(child, raw)"
          >
            <el-option
              v-for="choice in control(child).choices!"
              :key="choice.value"
              :label="choice.label"
              :value="choice.value"
            />
          </el-select>
          <el-select
            v-else-if="control(child).multiple"
            :model-value="literalOf(child)"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :placeholder="t('role.condition.listHint')"
            class="value"
            data-testid="condition-value"
            @update:model-value="(raw: unknown) => setLiteral(child, raw)"
          />
          <el-input-number
            v-else-if="control(child).type === 'number'"
            :model-value="(literalOf(child) as number | null) ?? undefined"
            :value-on-clear="null"
            controls-position="right"
            class="value"
            data-testid="condition-value"
            @update:model-value="(raw: number | null | undefined) => setLiteral(child, raw)"
          />
          <el-input
            v-else
            :model-value="(literalOf(child) as string | null) ?? ''"
            class="value"
            data-testid="condition-value"
            @update:model-value="(raw: string) => setLiteral(child, raw)"
          />
        </template>
        <el-button
          link
          type="danger"
          data-testid="condition-remove"
          @click="node.children.splice(index, 1)"
        >
          {{ t('role.condition.remove') }}
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.group.nested {
  padding: 6px 8px;
  border-left: 2px solid var(--el-border-color);
}
.group-head,
.rule {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.attr {
  width: 150px;
}
.op,
.mode {
  width: 110px;
}
.value {
  width: 200px;
}
</style>
