import { ApiError } from '@crane199709/sdk'
import type { Department } from '../organization/org-api'
import type { Role } from '../role/role-api'
import type { InviteInput } from './member-api'

/** 单个文件的数据行上限，超出时整个文件拒收。 */
export const MAX_ROWS = 500

// 固定并发：逐行调用现有邀请接口，既不压垮网关限流，也不必等太久。
const CONCURRENCY = 3

// 与服务端 SendCodeRequest.TARGET_PATTERN 及 254 字符上限一致，避免本地通过、接口拒绝。
const CONTACT_PATTERN = /^(?:[^@\s]+@[^@\s]+\.[^@\s]+|\+?[0-9]{6,15})$/
const CONTACT_MAX = 254

const ROLE_SEPARATOR = '、'
const PATH_SEPARATOR = '/'

/** 表格单元格的值，与解析库读出的类型一致。 */
export type Cell = string | number | boolean | Date | null

/** 行的本地校验问题，`key` 对应 `member.bulk.problem` 下的文案。 */
export interface RowProblem {
  key: string
  params?: Record<string, unknown>
}

/** 文件中的一个数据行；`input` 与 `problem` 二者有其一。 */
export interface BulkRow {
  /** 在表格中的行号，表头为第 1 行。 */
  line: number
  contact: string
  roles: string
  department: string
  input?: InviteInput
  problem?: RowProblem
}

export interface ParseContext {
  /** 模板的三列表头：联系方式、角色、主部门。 */
  headers: readonly [string, string, string]
  /** 可匹配的角色，含 Owner；没有 `iam:role:manage` 时为 null。 */
  roles: Role[] | null
  /** 可匹配的部门；没有 `iam:org:manage` 时为 null。 */
  departments: Department[] | null
}

export type ParseResult =
  { ok: true; rows: BulkRow[] } | { ok: false; reason: 'header' | 'tooMany' }

/** 读取工作簿第一个工作表；解析库按需加载，不进入首屏。 */
export async function readRows(file: Blob): Promise<Cell[][]> {
  const { readSheet } = await import('read-excel-file/universal')
  return (await readSheet(file)) as Cell[][]
}

/**
 * 生成模板：第一个工作表只有表头，第二个工作表是填写说明。
 * 说明放在模板里而不是页面上，填写时随手可查；上传时只读第一个工作表。
 */
export async function templateWorkbook(
  headers: ParseContext['headers'],
  guide: readonly [string, string, string],
  sheets: { data: string; guide: string },
): Promise<Blob> {
  const { default: writeXlsxFile } = await import('write-excel-file/universal')
  return writeXlsxFile([
    {
      sheet: sheets.data,
      data: [headers.map((value) => ({ value, fontWeight: 'bold' as const }))],
      columns: [{ width: 32 }, { width: 24 }, { width: 32 }],
    },
    {
      sheet: sheets.guide,
      data: headers.map((header, index) => [header, guide[index]!]),
      columns: [{ width: 12 }, { width: 72 }],
    },
  ]).toBlob()
}

/** 以给定文件名在浏览器中保存。 */
export function saveFile(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}

const text = (cell: Cell | undefined) =>
  cell === null || cell === undefined ? '' : String(cell).trim()

/** 按名称路径（从根到本级、`/` 拼接）索引部门；同一路径可能对应多个部门。 */
function departmentsByPath(departments: Department[]) {
  const byId = new Map(departments.map((item) => [item.id, item]))
  const result = new Map<string, string[]>()
  for (const item of departments) {
    const names: string[] = []
    // 用 seen 防御数据异常形成的环，避免死循环。
    const seen = new Set<string>()
    for (let node: Department | undefined = item; node && !seen.has(node.id);) {
      seen.add(node.id)
      names.unshift(node.name.trim())
      node = node.parentId ? byId.get(node.parentId) : undefined
    }
    const path = names.join(PATH_SEPARATOR)
    result.set(path, [...(result.get(path) ?? []), item.id])
  }
  return result
}

/**
 * 校验并匹配表格内容（含表头行）。表头不符或数据行超过上限时整个文件拒收；
 * 否则每个非空行各自校验，有问题的行带 `problem`，其余带可直接提交的 `input`。
 */
export function parseInvitations(data: Cell[][], context: ParseContext): ParseResult {
  const [header = [], ...body] = data
  const headerCells = header.map(text)
  if (
    context.headers.some((name, index) => headerCells[index] !== name) ||
    headerCells.slice(context.headers.length).some(Boolean)
  )
    return { ok: false, reason: 'header' }

  const lines = body
    .map((cells, index) => ({ cells: cells.map(text), line: index + 2 }))
    .filter(({ cells }) => cells.some(Boolean))
  if (lines.length > MAX_ROWS) return { ok: false, reason: 'tooMany' }

  const roleNames = new Map<string, Role[]>()
  for (const role of context.roles ?? []) {
    const name = role.name.trim()
    roleNames.set(name, [...(roleNames.get(name) ?? []), role])
  }
  const paths = departmentsByPath(context.departments ?? [])
  const seen = new Map<string, number>()

  return {
    ok: true,
    rows: lines.map(({ cells, line }) => {
      const [contact = '', roles = '', department = ''] = cells
      const row: BulkRow = { line, contact, roles, department }
      const fail = (key: string, params?: Record<string, unknown>) => ({
        ...row,
        problem: params ? { key, params } : { key },
      })

      if (!contact) return fail('contactRequired')
      if (contact.length > CONTACT_MAX || !CONTACT_PATTERN.test(contact))
        return fail('contactInvalid')
      // 邮箱不区分大小写，手机号原样比较。
      const identity = contact.includes('@') ? contact.toLowerCase() : contact
      const first = seen.get(identity)
      if (first !== undefined) return fail('contactDuplicate', { line: first })
      seen.set(identity, line)

      const roleIds: string[] = []
      const names = roles
        .split(ROLE_SEPARATOR)
        .map((name) => name.trim())
        .filter(Boolean)
      if (names.length && !context.roles) return fail('roleForbidden')
      for (const name of names) {
        const matched = roleNames.get(name) ?? []
        if (!matched.length) return fail('roleNotFound', { name })
        if (matched.length > 1) return fail('roleAmbiguous', { name })
        if (matched[0]!.owner) return fail('roleOwner', { name })
        if (!roleIds.includes(matched[0]!.id)) roleIds.push(matched[0]!.id)
      }

      let primaryDepartmentId: string | null = null
      if (department) {
        if (!context.departments) return fail('departmentForbidden')
        const path = department
          .split(PATH_SEPARATOR)
          .map((name) => name.trim())
          .join(PATH_SEPARATOR)
        const matched = paths.get(path) ?? []
        if (!matched.length) return fail('departmentNotFound', { path: department })
        if (matched.length > 1) return fail('departmentAmbiguous', { path: department })
        primaryDepartmentId = matched[0]!
      }

      return { ...row, input: { contact, roleIds, primaryDepartmentId } }
    }),
  }
}

/** 发送后的行结果，按规格第 5 节归类。 */
export type Outcome = 'invited' | 'resent' | 'alreadyMember' | 'failed'

export interface BulkResult {
  row: BulkRow
  outcome: Outcome
  /** 失败原因，仅 `failed` 有。 */
  reason?: string
}

/**
 * 以固定并发逐行调用邀请接口，返回与 `rows` 同序的结果。每行各自成败，失败不影响后面的行；
 * 不重试：5xx 时无法判断邮件是否已发出，重试可能重复发送。
 *
 * @param rows 有 `input` 的有效行
 * @param describe 把失败原因转为展示文案
 * @param onSettled 每行有结果时回调，用于显示进度
 */
export async function sendInvitations(
  rows: BulkRow[],
  invite: (input: InviteInput) => Promise<{ resent: boolean }>,
  describe: (cause: unknown) => string,
  onSettled?: (result: BulkResult) => void,
): Promise<BulkResult[]> {
  const results: BulkResult[] = new Array(rows.length)
  let next = 0
  async function worker() {
    while (next < rows.length) {
      const index = next++
      const row = rows[index]!
      let result: BulkResult
      try {
        const { resent } = await invite(row.input!)
        result = { row, outcome: resent ? 'resent' : 'invited' }
      } catch (cause) {
        result =
          cause instanceof ApiError && cause.code === 'member.alreadyMember'
            ? { row, outcome: 'alreadyMember' }
            : { row, outcome: 'failed', reason: describe(cause) }
      }
      results[index] = result
      onSettled?.(result)
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, rows.length) }, worker))
  return results
}
