import type { ConditionAttribute, ConditionJson } from './role-api'

/** 可视化编辑器的条件树；保存时转为后端的 JSON 表达式树，规则与后端 PolicyParser 一致。 */
export type Operator =
  'eq' | 'ne' | 'in' | 'nin' | 'gt' | 'gte' | 'lt' | 'lte' | 'contains' | 'is_null' | 'under'

export type Scalar = string | number | boolean

export type RuleValue =
  { kind: 'literal'; value: Scalar | Scalar[] | null } | { kind: 'ref'; ref: string }

export interface RuleNode {
  kind: 'rule'
  attr: string
  op: Operator | ''
  value: RuleValue
}

export interface GroupNode {
  kind: 'group'
  logic: 'and' | 'or'
  /** 整组取反，对应 `not`。 */
  negate: boolean
  children: ConditionNode[]
}

export type ConditionNode = RuleNode | GroupNode

/** 取值形态：无取值、单值、集合，或单值与集合皆可（`under` 可给一个或多个部门）。 */
export type ValueShape = 'none' | 'scalar' | 'list' | 'scalar_or_list'

export const emptyRule = (): RuleNode => ({
  kind: 'rule',
  attr: '',
  op: '',
  value: { kind: 'literal', value: null },
})

export const emptyGroup = (): GroupNode => ({
  kind: 'group',
  logic: 'and',
  negate: false,
  children: [emptyRule()],
})

/** 属性可用的操作符：集合属性只能判断包含与为空；大小比较限数值，「在子树下」限部门路径。 */
export function operatorsFor(attribute: ConditionAttribute): Operator[] {
  if (attribute.multiValued) return ['contains', 'is_null']
  const operators: Operator[] = ['eq', 'ne', 'in', 'nin']
  if (attribute.type === 'number') operators.push('gt', 'gte', 'lt', 'lte')
  if (attribute.type === 'ltree') operators.push('under')
  operators.push('is_null')
  return operators
}

export function shapeOf(op: Operator): ValueShape {
  if (op === 'is_null') return 'none'
  if (op === 'in' || op === 'nin') return 'list'
  if (op === 'under') return 'scalar_or_list'
  return 'scalar'
}

/** 可作为取值引用的属性：主体或环境属性，类型相同，单值与集合符合操作符的要求。 */
export function referencesFor(
  attribute: ConditionAttribute,
  op: Operator,
  candidates: ConditionAttribute[],
): ConditionAttribute[] {
  const shape = shapeOf(op)
  if (shape === 'none') return []
  return candidates.filter(
    (candidate) =>
      !candidate.name.startsWith('resource.') &&
      candidate.type === attribute.type &&
      (shape === 'scalar_or_list' || (shape === 'list') === candidate.multiValued),
  )
}

/** 选择属性或操作符后，原取值可能已不适用：操作符不可用时清空，取值形态变化时重置取值。 */
export function adjustRule(rule: RuleNode, attribute: ConditionAttribute | undefined): RuleNode {
  if (!attribute) return { ...emptyRule(), attr: rule.attr }
  const op = rule.op && operatorsFor(attribute).includes(rule.op) ? rule.op : ''
  if (!op) return { ...rule, op, value: { kind: 'literal', value: null } }
  const shape = shapeOf(op)
  const value = rule.value
  const fits =
    value.kind === 'ref' ||
    value.value === null ||
    (Array.isArray(value.value) ? shape !== 'scalar' : shape !== 'list')
  return {
    ...rule,
    op,
    value:
      fits && shape !== 'none' ? value : { kind: 'literal', value: shape === 'list' ? [] : null },
  }
}

/** 未填完的规则：缺属性、操作符，或需要取值却没有取值；空的条件组也不完整，空的「全部满足」会放开全部数据。 */
export function isComplete(node: ConditionNode): boolean {
  if (node.kind === 'group') return node.children.length > 0 && node.children.every(isComplete)
  if (!node.attr || !node.op) return false
  if (shapeOf(node.op) === 'none') return true
  if (node.value.kind === 'ref') return node.value.ref !== ''
  const value = node.value.value
  if (value === null || value === '') return false
  return !Array.isArray(value) || value.length > 0
}

export function toConditionJson(node: ConditionNode): ConditionJson {
  if (node.kind === 'rule') {
    const op = node.op as Operator
    if (shapeOf(op) === 'none') return { attr: node.attr, op }
    const value = node.value.kind === 'ref' ? { ref: node.value.ref } : node.value.value
    return { attr: node.attr, op, value }
  }
  const children = node.children.map(toConditionJson)
  const group: ConditionJson = node.logic === 'and' ? { and: children } : { or: children }
  return node.negate ? { not: group } : group
}

/** 读回已保存的条件；根节点总是一个组，便于编辑器统一处理。 */
export function fromConditionJson(json: ConditionJson): GroupNode {
  const node = toNode(json)
  return node.kind === 'group'
    ? node
    : { kind: 'group', logic: 'and', negate: false, children: [node] }
}

function toNode(json: ConditionJson): ConditionNode {
  if ('and' in json) return group('and', json.and)
  if ('or' in json) return group('or', json.or)
  if ('not' in json) {
    const inner = fromConditionJson(json.not)
    // 内层已取反时再包一层，保持 not(not x) 的结构
    return inner.negate
      ? { kind: 'group', logic: 'and', negate: true, children: [inner] }
      : { ...inner, negate: true }
  }
  const value = json.value
  return {
    kind: 'rule',
    attr: json.attr,
    op: json.op as Operator,
    value:
      value !== null && typeof value === 'object' && !Array.isArray(value) && 'ref' in value
        ? { kind: 'ref', ref: String((value as { ref: unknown }).ref) }
        : { kind: 'literal', value: (value ?? null) as Scalar | Scalar[] | null },
  }
}

function group(logic: 'and' | 'or', children: ConditionJson[]): GroupNode {
  return { kind: 'group', logic, negate: false, children: children.map(toNode) }
}
