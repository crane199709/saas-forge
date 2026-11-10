import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import writeXlsxFile from 'write-excel-file/universal'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createAccessControl, createTenantClient, tenantClientKey } from '@crane199709/sdk'
import { i18n } from '../i18n'
import MemberPage from './MemberPage.vue'
import type { Cell } from './bulk-invite'

beforeEach(() => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'admin' }),
  )
})

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const headers = ['联系方式', '角色', '主部门']
const page = { page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } }
const roles = [
  { id: 'r-owner', code: 'owner', name: '所有者', owner: true },
  { id: 'r-sales', code: 'sales', name: '销售', owner: false },
]
const departments = [
  { id: 'hq', parentId: null, name: '总部', path: 'hq' },
  { id: 'rd', parentId: 'hq', name: '研发部', path: 'hq.rd' },
]

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}
const ok = (data: unknown, meta?: unknown) =>
  json({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' })
const failure = (code: string, status: number, userTip: string) =>
  json({ code, httpStatus: status, userTip, traceId: 't' }, status)

/** 邀请接口按联系方式应答，其余接口给出列表数据；记录每个请求。 */
function backend(invitations: Record<string, () => Response>) {
  return vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url).pathname
    if (path === '/iam/members/invitations') {
      const { contact } = JSON.parse(String(init!.body)) as { contact: string }
      return Promise.resolve(invitations[contact]!())
    }
    if (path === '/iam/roles') return Promise.resolve(ok(roles))
    if (path === '/iam/org/departments') return Promise.resolve(ok(departments))
    return Promise.resolve(ok([], page))
  })
}

async function mountPage(fetcher: ReturnType<typeof vi.fn>, permissions: string[]) {
  vi.stubGlobal('fetch', fetcher)
  const access = createAccessControl({
    api: vi.fn().mockResolvedValue({ permissions, menus: [] }) as never,
  })
  await access.load()
  const wrapper = mount(MemberPage, {
    global: {
      plugins: [i18n, ElementPlus, access],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

const all = ['iam:member:manage', 'iam:role:manage', 'iam:org:manage']
const $ = (testId: string) => document.querySelector(`[data-testid="${testId}"]`) as HTMLElement

async function workbook(rows: Cell[][], name = 'members.xlsx') {
  const blob = await writeXlsxFile(rows as never).toBlob()
  return new File([blob], name)
}

async function upload(file: File) {
  const input = $('member-bulk-file') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  input.dispatchEvent(new Event('change'))
  // 先让上一次的提示随新选择清除，再等解析库按需加载，等待动态导入与文件读取完成。
  await nextTick()
  await vi.waitFor(() => {
    if (!$('member-bulk-preview') && !$('member-bulk-rejected')) throw new Error('pending')
  })
  await flushPromises()
}

async function open(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  await wrapper.get('[data-testid="member-bulk-invite"]').trigger('click')
  await flushPromises()
}

it('批量邀请入口需要成员管理权限', async () => {
  const wrapper = await mountPage(backend({}), [])
  expect(wrapper.find('[data-testid="member-bulk-invite"]').exists()).toBe(false)
})

it('非 .xlsx 或表头不符的文件整个拒收', async () => {
  const wrapper = await mountPage(backend({}), all)
  await open(wrapper)

  await upload(new File(['a,b'], 'members.csv'))
  expect($('member-bulk-rejected').textContent).toContain('只支持 .xlsx 文件')

  await upload(await workbook([['邮箱', '角色', '主部门']]))
  expect($('member-bulk-rejected').textContent).toContain('表头与模板不符')
  expect($('member-bulk-preview')).toBeNull()
})

it('预览有效人数与错误明细，只发送有效行，结束后显示各结果计数并刷新列表', async () => {
  const fetcher = backend({
    'new@example.com': () => ok({ membershipId: 'm1', status: 'invited', resent: false }),
    'again@example.com': () => ok({ membershipId: 'm2', status: 'invited', resent: true }),
    'member@example.com': () => failure('member.alreadyMember', 409, '该用户已是成员'),
    'full@example.com': () => failure('member.quotaExceeded', 409, '成员数已达上限'),
  })
  const wrapper = await mountPage(fetcher, all)
  await open(wrapper)

  await upload(
    await workbook([
      headers,
      ['new@example.com', '销售', '总部/研发部'],
      ['again@example.com', null, null],
      ['member@example.com', null, null],
      ['full@example.com', null, null],
      ['bad-contact', null, null],
      ['other@example.com', '所有者', null],
    ]),
  )
  expect($('member-bulk-preview').textContent).toContain('将邀请 4 人')
  expect($('member-bulk-preview').textContent).toContain('2 行有错误')
  expect($('member-bulk-preview').textContent).toContain('联系方式格式不正确')
  expect($('member-bulk-preview').textContent).toContain('不能预设所有者')

  const listCalls = fetcher.mock.calls.filter(([url]) => new URL(url).pathname === '/iam/members')
  $('member-bulk-send').click()
  await vi.waitFor(() => {
    if (!$('member-bulk-result')) throw new Error('pending')
  })
  await flushPromises()

  const invited = fetcher.mock.calls
    .filter(([url]) => new URL(url).pathname === '/iam/members/invitations')
    .map(([, init]) => JSON.parse(String(init!.body)))
  expect(invited).toHaveLength(4)
  expect(invited).toContainEqual({
    contact: 'new@example.com',
    roleIds: ['r-sales'],
    primaryDepartmentId: 'rd',
  })
  const result = $('member-bulk-result').textContent!
  expect(result).toMatch(/已邀请\s*1/)
  expect(result).toMatch(/已重发\s*1/)
  expect(result).toMatch(/已是成员\s*1/)
  expect(result).toMatch(/失败\s*3/)
  expect(result).toContain('成员数已达上限')
  expect(
    fetcher.mock.calls.filter(([url]) => new URL(url).pathname === '/iam/members'),
  ).toHaveLength(listCalls.length + 1)
})

it('没有有效行时不能确认；缺少角色与组织管理权限时填了对应列的行报错', async () => {
  const fetcher = backend({})
  const wrapper = await mountPage(fetcher, ['iam:member:manage'])
  await open(wrapper)
  // 没有对应权限时不加载角色与部门。
  expect(fetcher.mock.calls.map(([url]) => new URL(url).pathname)).not.toContain('/iam/roles')

  await upload(
    await workbook([headers, ['a@example.com', '销售', null], ['b@example.com', null, '总部']]),
  )
  expect($('member-bulk-preview').textContent).toContain('将邀请 0 人')
  expect($('member-bulk-preview').textContent).toContain('没有角色管理权限')
  expect($('member-bulk-preview').textContent).toContain('没有组织管理权限')
  expect(($('member-bulk-send') as HTMLButtonElement).disabled).toBe(true)
})
