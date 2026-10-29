import { expect, it } from 'vitest'
import {
  adjustRule,
  emptyRule,
  fromConditionJson,
  isComplete,
  operatorsFor,
  referencesFor,
  toConditionJson,
  type GroupNode,
} from './condition'
import type { ConditionAttribute, ConditionJson } from './role-api'

const amount: ConditionAttribute = {
  name: 'resource.amount',
  label: '金额',
  type: 'number',
  multiValued: false,
}
const departmentPath: ConditionAttribute = {
  name: 'resource.departmentPath',
  label: '归属部门',
  type: 'ltree',
  multiValued: false,
}
const ownerId: ConditionAttribute = {
  name: 'resource.ownerId',
  label: '归属成员',
  type: 'uuid',
  multiValued: false,
}
const subject: ConditionAttribute[] = [
  { name: 'subject.membershipId', label: null, type: 'uuid', multiValued: false },
  { name: 'subject.departmentPaths', label: null, type: 'ltree', multiValued: true },
  { name: 'subject.primaryDepartmentPath', label: null, type: 'ltree', multiValued: false },
  { name: 'subject.positionIds', label: null, type: 'uuid', multiValued: true },
  { name: 'subject.attributes.limit', label: '额度', type: 'number', multiValued: false },
  { name: 'env.now', label: null, type: 'number', multiValued: false },
]

it('操作符按属性类型限制：大小比较限数值，「在子树下」限部门路径，集合属性只能判断包含', () => {
  expect(operatorsFor(amount)).toEqual([
    'eq',
    'ne',
    'in',
    'nin',
    'gt',
    'gte',
    'lt',
    'lte',
    'is_null',
  ])
  expect(operatorsFor(departmentPath)).toEqual(['eq', 'ne', 'in', 'nin', 'under', 'is_null'])
  expect(operatorsFor(ownerId)).toEqual(['eq', 'ne', 'in', 'nin', 'is_null'])
  expect(operatorsFor(subject[3]!)).toEqual(['contains', 'is_null'])
})

it('可引用的属性须类型相同，单值与集合符合操作符', () => {
  const names = (attributes: ConditionAttribute[]) => attributes.map((item) => item.name)
  expect(names(referencesFor(ownerId, 'eq', subject))).toEqual(['subject.membershipId'])
  expect(names(referencesFor(departmentPath, 'in', subject))).toEqual(['subject.departmentPaths'])
  expect(names(referencesFor(departmentPath, 'under', subject))).toEqual([
    'subject.departmentPaths',
    'subject.primaryDepartmentPath',
  ])
  expect(names(referencesFor(amount, 'gt', subject))).toEqual([
    'subject.attributes.limit',
    'env.now',
  ])
  expect(referencesFor(amount, 'is_null', subject)).toEqual([])
})

it('换属性或操作符时清掉不再适用的操作符与取值', () => {
  const rule = { ...emptyRule(), attr: 'resource.amount', op: 'gt' as const }
  rule.value = { kind: 'literal', value: 1000 }
  expect(adjustRule({ ...rule, op: 'in' }, amount).value).toEqual({ kind: 'literal', value: [] })
  expect(adjustRule({ ...rule, attr: 'resource.ownerId' }, ownerId)).toMatchObject({
    op: '',
    value: { kind: 'literal', value: null },
  })
  expect(adjustRule(rule, amount)).toEqual(rule)
})

it('嵌套 and/or 与取反转为后端表达式树并能读回', () => {
  const json: ConditionJson = {
    or: [
      {
        and: [
          { attr: 'resource.amount', op: 'gt', value: 1000 },
          {
            attr: 'resource.departmentPath',
            op: 'under',
            value: { ref: 'subject.departmentPaths' },
          },
        ],
      },
      { not: { and: [{ attr: 'resource.ownerId', op: 'is_null' }] } },
      { attr: 'resource.amount', op: 'in', value: [1, 2] },
    ],
  }
  const tree = fromConditionJson(json)
  expect(tree.logic).toBe('or')
  expect((tree.children[1] as GroupNode).negate).toBe(true)
  expect(toConditionJson(tree)).toEqual(json)
  expect(isComplete(tree)).toBe(true)

  // 单条比较与 not(not x) 也能原样读回
  const single: ConditionJson = { attr: 'resource.amount', op: 'gte', value: 1 }
  expect(toConditionJson(fromConditionJson(single))).toEqual({ and: [single] })
  const twice: ConditionJson = { not: { not: { or: [single] } } }
  expect(toConditionJson(fromConditionJson(twice))).toEqual({
    not: { and: [{ not: { or: [single] } }] },
  })
})

it('缺属性、操作符或取值的规则与空条件组不完整', () => {
  expect(isComplete(emptyRule())).toBe(false)
  expect(isComplete({ kind: 'group', logic: 'and', negate: false, children: [] })).toBe(false)
  expect(isComplete({ ...emptyRule(), attr: 'resource.amount', op: 'gt' })).toBe(false)
  expect(isComplete({ ...emptyRule(), attr: 'resource.amount', op: 'is_null' })).toBe(true)
  expect(
    isComplete({
      kind: 'rule',
      attr: 'resource.amount',
      op: 'in',
      value: { kind: 'literal', value: [] },
    }),
  ).toBe(false)
  expect(
    isComplete({
      kind: 'rule',
      attr: 'resource.ownerId',
      op: 'eq',
      value: { kind: 'ref', ref: '' },
    }),
  ).toBe(false)
})
