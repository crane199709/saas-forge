import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@crane199709/sdk'
import { createOrganizationApi, departmentTree } from './org-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data: unknown, meta?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

it('按上级组装部门树，上级不存在的部门作为根', () => {
  const tree = departmentTree([
    { id: 'a', parentId: null, name: '总部', path: 'a' },
    { id: 'b', parentId: 'a', name: '研发', path: 'a.b' },
    { id: 'c', parentId: 'b', name: '平台组', path: 'a.b.c' },
    { id: 'd', parentId: 'gone', name: '孤立', path: 'gone.d' },
  ])
  expect(tree.map((node) => node.id)).toEqual(['a', 'd'])
  expect(tree[0]!.children[0]!.children[0]!.name).toBe('平台组')
})

it('以 JSON 调用 IAM 组织接口并带上当前 Token', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access-a', refreshToken: 'r', membershipId: 'm' }),
  )
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok(null))
    .mockResolvedValueOnce(
      ok([], { page: { number: 2, size: 20, totalElements: 21, totalPages: 2 } }),
    )
  vi.stubGlobal('fetch', fetcher)
  const api = createOrganizationApi(createTenantClient(sessionStorage))

  await api.moveDepartment('b', null)
  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/api/iam/org/departments/b/parent`)
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body)).toEqual({ parentId: null })
  expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
  expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-a')

  const page = await api.members(2, 20)
  expect(fetcher.mock.calls[1]![0]).toBe(
    `${location.origin}/api/iam/org/memberships?page=2&size=20`,
  )
  expect(page.page.totalElements).toBe(21)
})
