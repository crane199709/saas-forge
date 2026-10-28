import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createAccessControl, createTenantClient, tenantClientKey } from '@forge/sdk'
import { i18n } from '../i18n'
import MemberPage from './MemberPage.vue'
import type { Member } from './member-api'

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

const page = { page: { number: 1, size: 20, totalElements: 3, totalPages: 1 } }
const member = (id: string, status: Member['status'], owner = false): Member => ({
  membershipId: id,
  account: status === 'invited' ? null : id,
  displayName: `成员 ${id}`,
  email: `${id}@example.com`,
  phone: null,
  status,
  owner,
})

function ok(data: unknown, meta?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

async function mountPage(fetcher: ReturnType<typeof vi.fn>, permissions: string[] = []) {
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  const access = createAccessControl({
    api: vi.fn().mockResolvedValue({ permissions, menus: [] }) as never,
  })
  await access.load()
  const wrapper = mount(MemberPage, {
    global: { plugins: [i18n, ElementPlus, access], provide: { [tenantClientKey]: client } },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

it('按状态展示成员与可用操作，Owner 不可停用或移除；停用后重新加载列表', async () => {
  const rows = [
    member('owner', 'active', true),
    member('alice', 'active'),
    member('bob', 'invited'),
  ]
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok(rows, page))
    .mockResolvedValueOnce(ok(null))
    .mockResolvedValueOnce(ok(rows, page))
  const wrapper = await mountPage(fetcher)

  expect(wrapper.get('[data-testid="member-owner"]').text()).toContain('所有者')
  expect(wrapper.get('[data-testid="member-owner"]').findAll('button')).toHaveLength(0)
  expect(wrapper.get('[data-testid="member-bob"]').text()).toContain('邀请中')
  expect(wrapper.find('[data-testid="member-suspend-bob"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="member-remove-bob"]').exists()).toBe(true)

  await wrapper.get('[data-testid="member-suspend-alice"]').trigger('click')
  await flushPromises()
  ;(document.querySelector('[data-testid="member-confirm"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls[1]!
  expect(url).toBe(`${location.origin}/iam/members/alice/suspend`)
  expect(init.method).toBe('POST')
  expect(fetcher).toHaveBeenCalledTimes(3)
})

it('搜索与筛选作为查询参数提交，从第一页开始', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(ok([], page)))
  const wrapper = await mountPage(fetcher)

  await wrapper.get('input[data-testid="member-keyword"]').setValue(' alice ')
  await wrapper.get('[data-testid="member-search"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls.at(-1)![0]).toBe(
    `${location.origin}/iam/members?page=1&size=20&keyword=alice`,
  )
})

it('邀请时按权限展示预设角色，提交联系方式与所选角色', async () => {
  const roles = [
    {
      id: 'r-owner',
      code: 'owner',
      name: '所有者',
      builtin: true,
      owner: true,
      memberCount: 1,
      permissions: [],
    },
    {
      id: 'r-viewer',
      code: 'viewer',
      name: '查看员',
      builtin: false,
      owner: false,
      memberCount: 0,
      permissions: [],
    },
  ]
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([], page))
    .mockResolvedValueOnce(ok(roles))
    .mockResolvedValueOnce(ok({ membershipId: 'm-new', status: 'invited' }))
    .mockResolvedValueOnce(ok([], page))
  const wrapper = await mountPage(fetcher, ['iam:member:manage', 'iam:role:manage'])

  await wrapper.get('[data-testid="member-invite"]').trigger('click')
  await flushPromises()
  // 没有组织管理权限时不加载部门；Owner 不可预设。
  expect(fetcher.mock.calls[1]![0]).toBe(`${location.origin}/iam/roles`)
  expect(document.querySelector('[data-testid="member-invite-role-owner"]')).toBeNull()
  expect(document.querySelector('[data-testid="member-invite-department"]')).toBeNull()

  const input = document.querySelector(
    'input[data-testid="member-invite-contact"]',
  ) as HTMLInputElement
  input.value = 'new@example.com'
  input.dispatchEvent(new Event('input'))
  ;(
    document.querySelector('[data-testid="member-invite-role-viewer"] input') as HTMLElement
  ).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="member-invite-submit"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls[2]!
  expect(url).toBe(`${location.origin}/iam/members/invitations`)
  expect(JSON.parse(init.body)).toEqual({
    contact: 'new@example.com',
    roleIds: ['r-viewer'],
    primaryDepartmentId: null,
  })
  expect(fetcher).toHaveBeenCalledTimes(4)
})
