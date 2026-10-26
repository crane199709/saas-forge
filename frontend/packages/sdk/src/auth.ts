/** Tenant Realm 登录返回的会话；凭证只保存在当前浏览器标签页。 */
export interface TenantSession {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  identityName: string
  tenantName: string
}

/** 业务服务从已验签 JWT 返回的当前上下文。 */
export interface TenantContextResponse {
  identityId: string
  tenantId: string
  membershipId: string
  version: number
}

const storageKey = 'forge.tenant.session'

/** Shell 创建并提供的客户端类型，业务组件复用同一刷新队列。 */
export type TenantClient = ReturnType<typeof createTenantClient>

/** Shell 通过 Vue provide 提供客户端，Sub-App 通过同一个 SDK 单例中的 key 注入。 */
export const tenantClientKey = Symbol('forge.tenant.client')

/** 创建同源 Tenant Realm 客户端；Shell 持有一个实例，Sub-App 共享该实例。 */
export function createTenantClient(storage: Storage) {
  let pendingRefresh: Promise<TenantSession> | undefined
  let generation = 0

  function session(): TenantSession | null {
    try {
      const value = JSON.parse(storage.getItem(storageKey) ?? 'null') as TenantSession | null
      if (
        value &&
        typeof value.accessToken === 'string' &&
        typeof value.refreshToken === 'string'
      ) {
        return value
      }
    } catch {
      // 历史或损坏的存储不能阻止登录页启动。
    }
    storage.removeItem(storageKey)
    return null
  }

  function clear() {
    generation += 1
    pendingRefresh = undefined
    storage.removeItem(storageKey)
  }

  async function logout() {
    clear()
    try {
      const response = await fetch('/iam/auth/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
      if (!response.ok) throw new Error('auth.logoutFailed')
    } catch {
      throw new Error('auth.logoutFailed')
    }
  }

  async function exchange(path: string, body: object): Promise<TenantSession> {
    const started = generation
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!response.ok) {
      throw new Error('auth.failed')
    }
    const value = (await response.json()) as TenantSession
    if (started !== generation) throw new Error('auth.sessionChanged')
    storage.setItem(storageKey, JSON.stringify(value))
    return value
  }

  async function login(account: string, password: string) {
    clear()
    const started = generation
    const verifier = base64Url(crypto.getRandomValues(new Uint8Array(32)))
    const state = base64Url(crypto.getRandomValues(new Uint8Array(32)))
    const challenge = base64Url(
      new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))),
    )
    const authenticated = await fetch('/iam/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account, password }),
    })
    if (!authenticated.ok) {
      if (authenticated.status === 409) {
        const error = (await authenticated.json()) as { code?: string }
        if (error.code === 'auth.membershipSelectionRequired') throw new Error(error.code)
      }
      throw new Error('auth.failed')
    }
    const settings = (await authenticated.json()) as { clientId: string; redirectUri: string }
    // 回调必须与 Shell 同源；fetch 跟随标准授权重定向，不执行回调 HTML。
    if (new URL(settings.redirectUri).origin !== location.origin)
      throw new Error('auth.invalidRedirect')
    const parameters = new URLSearchParams({
      response_type: 'code',
      client_id: settings.clientId,
      redirect_uri: settings.redirectUri,
      scope: 'tenant',
      state,
      code_challenge_method: 'S256',
      code_challenge: challenge,
    })
    const authorized = await fetch(`/iam/oauth2/authorize?${parameters}`, {
      credentials: 'same-origin',
    })
    const callback = new URL(authorized.url)
    const code = callback.searchParams.get('code')
    if (
      !authorized.ok ||
      callback.origin !== location.origin ||
      callback.searchParams.get('state') !== state ||
      !code
    ) {
      throw new Error('auth.failed')
    }
    if (started !== generation) throw new Error('auth.sessionChanged')
    return exchange('/iam/auth/exchange', { code, codeVerifier: verifier })
  }

  function refresh(): Promise<TenantSession> {
    if (pendingRefresh) return pendingRefresh
    const value = session()
    if (!value) return Promise.reject(new Error('auth.failed'))
    const started = generation
    const operation = exchange('/iam/auth/refresh', { refreshToken: value.refreshToken })
      .catch((error: unknown) => {
        if (started === generation) clear()
        throw error
      })
      .finally(() => {
        if (pendingRefresh === operation) pendingRefresh = undefined
      })
    pendingRefresh = operation
    return operation
  }

  async function request(path: string, init: RequestInit = {}): Promise<Response> {
    // 只接受同源路径，防止业务调用把共享凭证带到外部地址。
    const target = new URL(path, location.origin)
    if (!path.startsWith('/') || target.origin !== location.origin) {
      throw new Error('auth.invalidPath')
    }
    const started = generation
    const send = () => {
      const headers = new Headers(init.headers)
      const value = session()
      if (value) headers.set('Authorization', `Bearer ${value.accessToken}`)
      return fetch(target.href, { ...init, headers, credentials: 'omit', redirect: 'error' })
    }
    let response = await send()
    if (started !== generation) throw new Error('auth.sessionChanged')
    if (response.status === 401 && session()) {
      await refresh()
      response = await send()
      if (started !== generation) throw new Error('auth.sessionChanged')
      if (response.status === 401) clear()
    }
    return response
  }

  return { login, logout, refresh, request, session, clear }
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '')
}
