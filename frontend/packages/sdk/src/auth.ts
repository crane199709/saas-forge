import type { InjectionKey } from 'vue'

/** Tenant Realm 登录返回的会话；凭证只保存在当前浏览器标签页。 */
export interface TenantSession {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  identityName: string
  /** 当前 Active Tenant；切换租户后随新 Token 一起变化。 */
  tenantId: string
  tenantName: string
  /** 当前 Token 绑定的 Membership。 */
  membershipId: string
}

/** 当前身份可进入的一个有效 Membership。 */
export interface TenantMembership {
  membershipId: string
  tenantId: string
  tenantName: string
}

/**
 * 账号密码校验通过后的结果：唯一的有效 Membership 自动进入；多个时须调用 `selectTenant`；
 * 没有时应展示无租户页，不会签发 Token。
 */
export type TenantLoginResult =
  | { status: 'signedIn'; session: TenantSession }
  | { status: 'selectTenant'; memberships: TenantMembership[] }
  | { status: 'noTenant' }

/** 会话变化（登录、刷新、切换、清除）时的回调；`null` 表示已无会话。 */
export type TenantSessionListener = (session: TenantSession | null) => void

const storageKey = 'forge.tenant.session'

/** Shell 创建并提供的客户端类型，业务组件复用同一刷新队列。 */
export type TenantClient = ReturnType<typeof createTenantClient>

/** Shell 通过 Vue provide 提供客户端，Sub-App 通过同一个 SDK 单例中的 key 注入。 */
export const tenantClientKey: InjectionKey<TenantClient> = Symbol('forge.tenant.client')

/** 创建同源 Tenant Realm 客户端；Shell 持有一个实例，Sub-App 共享该实例。 */
export function createTenantClient(storage: Storage) {
  // 刷新与切换都会轮换同一个 Refresh Token，必须串行，共用这一个队列。
  let pendingRotation: Promise<TenantSession> | undefined
  let generation = 0
  // 登录会话已建立、尚未选定租户时的授权参数；只在同一 generation 内有效。
  let authorization: { clientId: string; redirectUri: string; generation: number } | undefined
  const listeners = new Set<TenantSessionListener>()

  function store(value: TenantSession | null) {
    if (value) storage.setItem(storageKey, JSON.stringify(value))
    else storage.removeItem(storageKey)
    for (const listener of listeners) listener(value)
  }

  /** 订阅会话变化，返回取消订阅函数。 */
  function onSessionChange(listener: TenantSessionListener): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }

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
    pendingRotation = undefined
    authorization = undefined
    store(null)
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
    if (response.status === 403) throw new Error('auth.tenantAccessDenied')
    if (!response.ok) {
      throw new Error('auth.failed')
    }
    const value = (await response.json()) as TenantSession
    if (started !== generation) throw new Error('auth.sessionChanged')
    store(value)
    return value
  }

  /** 校验账号密码并建立 IAM 登录会话，按可进入的 Membership 数量决定自动进入、选择租户或无租户。 */
  async function login(account: string, password: string): Promise<TenantLoginResult> {
    clear()
    const started = generation
    const authenticated = await fetch('/iam/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account, password }),
    })
    if (!authenticated.ok) throw new Error('auth.failed')
    const settings = (await authenticated.json()) as {
      clientId: string
      redirectUri: string
      memberships: TenantMembership[]
    }
    // 回调必须与 Shell 同源；fetch 跟随标准授权重定向，不执行回调 HTML。
    if (new URL(settings.redirectUri).origin !== location.origin)
      throw new Error('auth.invalidRedirect')
    if (started !== generation) throw new Error('auth.sessionChanged')
    const memberships = settings.memberships ?? []
    if (memberships.length === 0) return { status: 'noTenant' }
    authorization = { clientId: settings.clientId, redirectUri: settings.redirectUri, generation }
    if (memberships.length > 1) return { status: 'selectTenant', memberships }
    return { status: 'signedIn', session: await selectTenant(memberships[0]!.membershipId) }
  }

  /** 以 `login` 建立的 IAM 登录会话进入指定 Membership 所在租户，签发绑定该租户的 Token。 */
  async function selectTenant(membershipId: string): Promise<TenantSession> {
    const settings = authorization
    if (!settings || settings.generation !== generation) throw new Error('auth.failed')
    const started = generation
    const verifier = base64Url(crypto.getRandomValues(new Uint8Array(32)))
    const state = base64Url(crypto.getRandomValues(new Uint8Array(32)))
    const challenge = base64Url(
      new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))),
    )
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
    const value = await exchange('/iam/auth/exchange', {
      code,
      codeVerifier: verifier,
      membershipId,
    })
    authorization = undefined
    return value
  }

  /** 列出当前身份可进入的有效 Membership，供租户切换器使用。 */
  async function memberships(): Promise<TenantMembership[]> {
    const response = await request('/iam/auth/memberships')
    if (!response.ok) throw new Error('auth.membershipsFailed')
    return (await response.json()) as TenantMembership[]
  }

  /**
   * 用当前 Refresh Token 换发绑定目标 Membership 的新会话；被拒绝时保留原会话。
   * 成功后切换前发出的请求一律以 `auth.sessionChanged` 失败，调用方应清空租户相关状态并重新加载。
   */
  function switchTenant(membershipId: string): Promise<TenantSession> {
    const previous = pendingRotation ?? Promise.resolve(undefined)
    const operation = previous
      .catch(() => undefined)
      .then(async () => {
        const value = session()
        if (!value) throw new Error('auth.failed')
        const started = generation
        const response = await fetch('/iam/auth/switch', {
          method: 'POST',
          credentials: 'omit',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: value.refreshToken, membershipId }),
        })
        if (started !== generation) throw new Error('auth.sessionChanged')
        if (response.status === 403) throw new Error('auth.tenantAccessDenied')
        // 只有刷新凭证被拒绝才说明会话已失效；网关或服务端故障时保留原会话以便重试。
        if (response.status === 401) {
          clear()
          throw new Error('auth.failed')
        }
        if (!response.ok) throw new Error('auth.switchFailed')
        const next = (await response.json()) as TenantSession
        if (started !== generation) throw new Error('auth.sessionChanged')
        generation += 1
        store(next)
        return next
      })
      .finally(() => {
        if (pendingRotation === operation) pendingRotation = undefined
      })
    pendingRotation = operation
    return operation
  }

  function refresh(): Promise<TenantSession> {
    if (pendingRotation) return pendingRotation
    const value = session()
    if (!value) return Promise.reject(new Error('auth.failed'))
    const started = generation
    const operation = exchange('/iam/auth/refresh', { refreshToken: value.refreshToken })
      .catch((error: unknown) => {
        if (started === generation) clear()
        throw error
      })
      .finally(() => {
        if (pendingRotation === operation) pendingRotation = undefined
      })
    pendingRotation = operation
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
      // 等到的可能是一次租户切换，不能把旧租户发起的请求带着新租户 Token 重发。
      if (started !== generation) throw new Error('auth.sessionChanged')
      response = await send()
      if (started !== generation) throw new Error('auth.sessionChanged')
      if (response.status === 401) clear()
    }
    return response
  }

  return {
    login,
    selectTenant,
    memberships,
    switchTenant,
    onSessionChange,
    logout,
    refresh,
    request,
    session,
    clear,
  }
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '')
}
