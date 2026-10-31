/// <reference types="node" />
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { createTenantClient, type TenantSession } from '@crane199709/sdk'

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

const key = 'forge.tenant.session'

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function unauthenticated() {
  return new Response(
    JSON.stringify({ code: 'auth.unauthenticated', httpStatus: 401, traceId: 't' }),
    { status: 401, headers: { 'Content-Type': 'application/json' } },
  )
}

function session(access: string, refresh: string, membership = 'a'): TenantSession {
  return {
    accessToken: access,
    refreshToken: refresh,
    tokenType: 'Bearer',
    expiresIn: 900,
    identityName: '多成员身份',
    tenantId: `tenant-${membership}`,
    tenantName: `租户 ${membership.toUpperCase()}`,
    membershipId: `membership-${membership}`,
  }
}

function signedIn(value = session('access-a', 'refresh-a')) {
  localStorage.setItem(key, JSON.stringify(value))
}

/** 模拟其他标签页改写会话：浏览器只向其他标签页派发 storage 事件。 */
function otherTab(value: TenantSession | null) {
  const oldValue = localStorage.getItem(key)
  const newValue = value ? JSON.stringify(value) : null
  if (newValue) localStorage.setItem(key, newValue)
  else localStorage.removeItem(key)
  window.dispatchEvent(
    new StorageEvent('storage', { key, oldValue, newValue, storageArea: localStorage }),
  )
}

function pending() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>((done) => (resolve = done))
  return { promise, resolve }
}

describe('其他标签页改写会话', () => {
  it('刷新 Token 时通知订阅者，在途请求不作废', async () => {
    signedIn()
    const orders = pending()
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(orders.promise))
    const client = createTenantClient(localStorage)
    const changes: [string | undefined, boolean][] = []
    client.onSessionChange((value, change) => changes.push([value?.accessToken, change.crossTab]))
    const request = client.request('/demo/orders')
    otherTab(session('access-a2', 'refresh-a2'))
    expect(changes).toEqual([['access-a2', true]])
    expect(client.session()?.accessToken).toBe('access-a2')
    orders.resolve(ok([]))
    expect((await request).status).toBe(200)
  })

  it('切换租户时在途请求以 auth.sessionChanged 失败，遇到 401 也不带新 Token 重发', async () => {
    signedIn()
    const orders = pending()
    const write = pending()
    const fetcher = vi.fn().mockReturnValueOnce(orders.promise).mockReturnValueOnce(write.promise)
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(localStorage)
    const tenants: (string | undefined)[] = []
    client.onSessionChange((value) => tenants.push(value?.tenantId))
    const staleOrders = client.request('/demo/orders')
    const staleWrite = client.request('/demo/orders', { method: 'POST', body: '{}' })
    otherTab(session('access-b', 'refresh-b', 'b'))
    expect(tenants).toEqual(['tenant-b'])
    orders.resolve(ok([]))
    write.resolve(unauthenticated())
    await expect(staleOrders).rejects.toThrow('auth.sessionChanged')
    await expect(staleWrite).rejects.toThrow('auth.sessionChanged')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('退出或清除会话时通知订阅者已无会话，在途请求作废', async () => {
    signedIn()
    const orders = pending()
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(orders.promise))
    const client = createTenantClient(localStorage)
    const changes: (TenantSession | null)[] = []
    client.onSessionChange((value) => changes.push(value))
    const request = client.request('/demo/orders')
    otherTab(null)
    expect(changes).toEqual([null])
    expect(client.session()).toBeNull()
    orders.resolve(ok([]))
    await expect(request).rejects.toThrow('auth.sessionChanged')
  })

  it('浏览器清空存储（key 为 null）时同样视为会话清除', () => {
    signedIn()
    const client = createTenantClient(localStorage)
    const changes: (TenantSession | null)[] = []
    client.onSessionChange((value) => changes.push(value))
    localStorage.clear()
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
    expect(changes).toEqual([null])
  })

  it('登录时停留在登录的标签页收到新会话', () => {
    const client = createTenantClient(localStorage)
    const changes: [string | undefined, boolean][] = []
    client.onSessionChange((value, change) => changes.push([value?.tenantId, change.crossTab]))
    otherTab(session('access-a', 'refresh-a'))
    expect(changes).toEqual([['tenant-a', true]])
    expect(client.session()?.accessToken).toBe('access-a')
  })

  it('忽略其他存储键与 sessionStorage 的变化', () => {
    signedIn()
    const client = createTenantClient(localStorage)
    const listener = vi.fn()
    client.onSessionChange(listener)
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'forge.platform.session', storageArea: localStorage }),
    )
    window.dispatchEvent(new StorageEvent('storage', { key, storageArea: sessionStorage }))
    expect(listener).not.toHaveBeenCalled()
  })
})

describe('跨标签页刷新互斥', () => {
  it('两个标签页同时刷新只调用一次刷新接口，两者都拿到新会话', async () => {
    signedIn()
    const fetcher = vi.fn().mockImplementation((path: string, init?: RequestInit) => {
      if (path === '/iam/auth/refresh') {
        expect(JSON.parse(String(init?.body)).refreshToken).toBe('refresh-a')
        return Promise.resolve(ok(session('access-a2', 'refresh-a2')))
      }
      const token = new Headers(init?.headers).get('Authorization')
      return Promise.resolve(token === 'Bearer access-a2' ? ok({}) : unauthenticated())
    })
    vi.stubGlobal('fetch', fetcher)
    const tabA = createTenantClient(localStorage)
    const tabB = createTenantClient(localStorage)
    const [a, b] = await Promise.all([tabA.request('/demo/orders'), tabB.request('/demo/orders')])
    expect(a.status).toBe(200)
    expect(b.status).toBe(200)
    expect(fetcher.mock.calls.filter(([path]) => path === '/iam/auth/refresh')).toHaveLength(1)
    expect(tabA.session()?.refreshToken).toBe('refresh-a2')
    expect(tabB.session()?.refreshToken).toBe('refresh-a2')
  })

  it('切换租户等待其他标签页的刷新完成，并使用轮换后的刷新凭证', async () => {
    signedIn()
    const refreshed = pending()
    const fetcher = vi
      .fn()
      .mockReturnValueOnce(refreshed.promise)
      .mockResolvedValueOnce(ok(session('access-b', 'refresh-b', 'b')))
    vi.stubGlobal('fetch', fetcher)
    const tabA = createTenantClient(localStorage)
    const tabB = createTenantClient(localStorage)
    const refreshing = tabA.refresh()
    const switching = tabB.switchTenant('membership-b')
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1))
    refreshed.resolve(ok(session('access-a2', 'refresh-a2')))
    await refreshing
    expect((await switching).tenantId).toBe('tenant-b')
    expect(JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body)).refreshToken).toBe('refresh-a2')
  })

  it('等锁期间其他标签页切换租户时，刷新以 auth.sessionChanged 失败且不调用接口', async () => {
    signedIn()
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    let release!: () => void
    const held = navigator.locks.request(
      'forge.tenant.rotation',
      () => new Promise<void>((done) => (release = done)),
    )
    await vi.waitFor(() => expect(release).toBeDefined())
    const client = createTenantClient(localStorage)
    const refreshing = client.refresh()
    otherTab(session('access-b', 'refresh-b', 'b'))
    release()
    await held
    await expect(refreshing).rejects.toThrow('auth.sessionChanged')
    expect(fetcher).not.toHaveBeenCalled()
    expect(client.session()?.tenantId).toBe('tenant-b')
  })
})
