import { describe, expect, it } from 'vitest'
import { ApiError } from '@crane199709/sdk'
import type { Department } from '../organization/org-api'
import type { Role } from '../role/role-api'
import {
  failureWorkbook,
  parseInvitations,
  readRows,
  sendInvitations,
  type BulkRow,
  type Cell,
  type ParseContext,
} from './bulk-invite'

const headers = ['联系方式', '角色', '主部门'] as const
const role = (id: string, name: string, owner = false): Role => ({
  id,
  code: id,
  name,
  builtin: owner,
  owner,
  memberCount: 0,
  permissions: [],
  dataPolicies: [],
})
const department = (id: string, parentId: string | null, name: string): Department => ({
  id,
  parentId,
  name,
  path: id,
})
const roles = [
  role('r-owner', '所有者', true),
  role('r-sales', '销售'),
  role('r-ops', '运营'),
  role('r-dup-1', '审批人'),
  role('r-dup-2', '审批人'),
]
const departments = [
  department('hq', null, '总部'),
  department('rd', 'hq', '研发部'),
  department('be', 'rd', '后端组'),
  department('be2', 'rd', '后端组'),
  department('ops', 'hq', '运营部'),
]
const context: ParseContext = { headers, reasonHeader: '失败原因', roles, departments }

function parse(rows: Cell[][], overrides: Partial<ParseContext> = {}) {
  return parseInvitations([[...headers], ...rows], { ...context, ...overrides })
}

function problems(rows: Cell[][], overrides: Partial<ParseContext> = {}) {
  const result = parse(rows, overrides)
  if (!result.ok) throw new Error(result.reason)
  return result.rows.map((row) => row.problem ?? null)
}

describe('parseInvitations', () => {
  it('表头与模板不符时整个文件拒收', () => {
    expect(parseInvitations([['邮箱', '角色', '主部门']], context)).toEqual({
      ok: false,
      reason: 'header',
    })
    expect(parseInvitations([], context)).toEqual({ ok: false, reason: 'header' })
    expect(parseInvitations([[...headers, '备注']], context)).toEqual({
      ok: false,
      reason: 'header',
    })
  })

  it('忽略失败清单末尾的「失败原因」列，只有该列有值的行视为空行', () => {
    const result = parseInvitations(
      [
        [...headers, '失败原因'],
        ['a@example.com', null, null, '服务暂不可用'],
        [null, null, null, '缺少联系方式'],
      ],
      context,
    )
    expect(result).toEqual({
      ok: true,
      rows: [
        {
          line: 2,
          contact: 'a@example.com',
          roles: '',
          department: '',
          input: { contact: 'a@example.com', roleIds: [], primaryDepartmentId: null },
        },
      ],
    })
    expect(parseInvitations([[...headers, '失败原因', '备注']], context)).toEqual({
      ok: false,
      reason: 'header',
    })
  })

  it('超过 500 个数据行时整个文件拒收，全空行不计入且不出现在结果中', () => {
    const rows: Cell[][] = Array.from({ length: 500 }, (_, i) => [`u${i}@example.com`, null, null])
    const exact = parse([...rows, [null, '', ' ']])
    expect(exact.ok && exact.rows).toHaveLength(500)
    expect(parse([...rows, ['extra@example.com', null, null]])).toEqual({
      ok: false,
      reason: 'tooMany',
    })
  })

  it('有效行转为邀请参数，行号按表格中的位置计', () => {
    const result = parse([
      [' Alice@Example.com ', '销售、 运营', '总部 / 研发部'],
      [null, null, null],
      [13800000000, null, null],
    ])
    expect(result).toEqual({
      ok: true,
      rows: [
        {
          line: 2,
          contact: 'Alice@Example.com',
          roles: '销售、 运营',
          department: '总部 / 研发部',
          input: {
            contact: 'Alice@Example.com',
            roleIds: ['r-sales', 'r-ops'],
            primaryDepartmentId: 'rd',
          },
        },
        {
          line: 4,
          contact: '13800000000',
          roles: '',
          department: '',
          input: { contact: '13800000000', roleIds: [], primaryDepartmentId: null },
        },
      ],
    })
  })

  it('联系方式按邀请接口的规则校验，文件内重复的后出现行报错，邮箱不区分大小写', () => {
    expect(
      problems([
        [null, '销售', null],
        ['not-an-email', null, null],
        ['12345', null, null],
        [`${'a'.repeat(250)}@x.cn`, null, null],
        ['+8613800000000', null, null],
        ['bob@example.com', null, null],
        ['BOB@example.com', null, null],
        ['+8613800000000', null, null],
      ]),
    ).toEqual([
      { key: 'contactRequired' },
      { key: 'contactInvalid' },
      { key: 'contactInvalid' },
      { key: 'contactInvalid' },
      null,
      null,
      { key: 'contactDuplicate', params: { line: 7 } },
      { key: 'contactDuplicate', params: { line: 6 } },
    ])
  })

  it('联系方式中间的空白按邀请接口的规则只认 ASCII 空白，全角空格与不换行空格不算空白', () => {
    expect(
      problems([
        ['bob smith@example.com', null, null],
        ['bob\u3000smith@example.com', null, null],
        ['bob\u00a0smith@example.com', null, null],
      ]),
    ).toEqual([{ key: 'contactInvalid' }, null, null])
  })

  it('角色按名称匹配：不存在、重名或 Owner 时该行报错', () => {
    expect(
      problems([
        ['a@example.com', '销售、不存在', null],
        ['b@example.com', '审批人', null],
        ['c@example.com', '所有者', null],
        ['d@example.com', '、销售、', null],
      ]),
    ).toEqual([
      { key: 'roleNotFound', params: { name: '不存在' } },
      { key: 'roleAmbiguous', params: { name: '审批人' } },
      { key: 'roleOwner', params: { name: '所有者' } },
      null,
    ])
  })

  it('主部门按从根开始的名称路径匹配：不存在或路径重复时该行报错', () => {
    expect(
      problems([
        ['a@example.com', null, '总部/研发部'],
        ['b@example.com', null, '研发部'],
        ['c@example.com', null, '总部/研发部/后端组'],
        ['d@example.com', null, '总部/运营部'],
      ]),
    ).toEqual([
      null,
      { key: 'departmentNotFound', params: { path: '研发部' } },
      { key: 'departmentAmbiguous', params: { path: '总部/研发部/后端组' } },
      null,
    ])
  })

  it('缺少角色或组织管理权限时，填了对应列的行报错', () => {
    expect(
      problems(
        [
          ['a@example.com', '销售', null],
          ['b@example.com', null, '总部'],
          ['c@example.com', null, null],
        ],
        { roles: null, departments: null },
      ),
    ).toEqual([{ key: 'roleForbidden' }, { key: 'departmentForbidden' }, null])
  })
})

describe('sendInvitations', () => {
  const row = (line: number): BulkRow => ({
    line,
    contact: `u${line}@example.com`,
    roles: '',
    department: '',
    input: { contact: `u${line}@example.com`, roleIds: [], primaryDepartmentId: null },
  })
  const apiError = (code: string, status: number) =>
    new ApiError({ code, httpStatus: status, userTip: `tip ${code}`, traceId: 'trace' })

  it('按接口结果逐行归类，单行失败不影响其他行，不重试', async () => {
    const calls: string[] = []
    const outcomes: Record<string, () => Promise<{ resent: boolean }>> = {
      'u2@example.com': () => Promise.resolve({ resent: false }),
      'u3@example.com': () => Promise.resolve({ resent: true }),
      'u4@example.com': () => Promise.reject(apiError('member.alreadyMember', 409)),
      'u5@example.com': () => Promise.reject(apiError('member.quotaExceeded', 409)),
      'u6@example.com': () => Promise.reject(new TypeError('Failed to fetch')),
      'u7@example.com': () => Promise.reject(apiError('gateway.rateLimited', 429)),
      'u8@example.com': () => Promise.reject(apiError('system.internalError', 500)),
      'u9@example.com': () => Promise.resolve({ resent: false }),
    }
    const results = await sendInvitations(
      [2, 3, 4, 5, 6, 7, 8, 9].map(row),
      (input) => {
        calls.push(input.contact)
        return outcomes[input.contact]!()
      },
      (cause) => (cause instanceof ApiError ? cause.userTip! : '网络错误'),
    )
    expect(calls).toHaveLength(8)
    expect(results.map((result) => [result.row.line, result.outcome, result.reason])).toEqual([
      [2, 'invited', undefined],
      [3, 'resent', undefined],
      [4, 'alreadyMember', undefined],
      [5, 'failed', 'tip member.quotaExceeded'],
      [6, 'failed', '网络错误'],
      [7, 'failed', 'tip gateway.rateLimited'],
      [8, 'failed', 'tip system.internalError'],
      [9, 'invited', undefined],
    ])
  })

  it('同时最多发出 3 个请求，并逐行报告进度', async () => {
    let inFlight = 0
    let peak = 0
    const settled: number[] = []
    await sendInvitations(
      [2, 3, 4, 5, 6, 7, 8].map(row),
      async () => {
        inFlight++
        peak = Math.max(peak, inFlight)
        await new Promise((resolve) => setTimeout(resolve, 1))
        inFlight--
        return { resent: false }
      },
      () => '',
      (result) => settled.push(result.row.line),
    )
    expect(peak).toBe(3)
    expect(settled.sort()).toEqual([2, 3, 4, 5, 6, 7, 8])
  })

  it('停止后已发出的请求等待完成，其余行标为未处理', async () => {
    const stop = { stopped: false }
    const pending: Array<() => void> = []
    const calls: string[] = []
    const sending = sendInvitations(
      [2, 3, 4, 5, 6].map(row),
      (input) => {
        calls.push(input.contact)
        return new Promise((resolve) => pending.push(() => resolve({ resent: false })))
      },
      () => '',
      undefined,
      () => stop.stopped,
    )
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(calls).toHaveLength(3)
    stop.stopped = true
    pending.forEach((resolve) => resolve())
    const results = await sending
    expect(calls).toHaveLength(3)
    expect(results.map((result) => [result.row.line, result.outcome])).toEqual([
      [2, 'invited'],
      [3, 'invited'],
      [4, 'invited'],
      [5, 'skipped'],
      [6, 'skipped'],
    ])
  })
})

describe('failureWorkbook', () => {
  const template = {
    headers,
    guide: ['必填', '选填', '选填'] as const,
    sheets: { data: '成员', guide: '填写说明' },
  }

  it('失败清单与模板同格式，末尾多一列失败原因，修正后可直接重传', async () => {
    const failed: BulkRow[] = [
      { line: 3, contact: '13800000000', roles: '销售', department: '总部/研发部' },
      { line: 5, contact: '', roles: '运营', department: '' },
    ]
    const blob = await failureWorkbook(template, '失败原因', [
      { row: failed[0]!, reason: '服务暂不可用' },
      { row: failed[1]!, reason: '缺少联系方式' },
    ])
    const data = await readRows(blob)
    expect(data).toEqual([
      ['联系方式', '角色', '主部门', '失败原因'],
      ['13800000000', '销售', '总部/研发部', '服务暂不可用'],
      [null, '运营', null, '缺少联系方式'],
    ])
    const result = parseInvitations(data, context)
    expect(
      result.ok && result.rows.map((item) => [item.contact, item.input ?? item.problem]),
    ).toEqual([
      ['13800000000', { contact: '13800000000', roleIds: ['r-sales'], primaryDepartmentId: 'rd' }],
      ['', { key: 'contactRequired' }],
    ])
  })
})
