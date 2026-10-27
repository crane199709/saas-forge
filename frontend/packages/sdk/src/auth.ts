import type { InjectionKey } from 'vue'
import { ApiError, readEnvelope, readPage, type Page } from './api'

/**
 * 一个 Realm 的认证接口位置与凭证存储（ADR-0003）。每个 Shell App 只使用一个 Realm：
 * tenant-app 用 {@link tenantRealm}，platform-console 用 {@link platformRealm}。
 */
export interface RealmConfig {
  /** 会话在 Storage 中的键；不同 Realm 不能相同，避免一个 Realm 读到另一个 Realm 的凭证。 */
  storageKey: string
  /** 该 Realm 认证接口的同源路径前缀，其下有 `login`、`logout`、`exchange`、`refresh`。 */
  authPath: string
  /** 授权请求的 scope，与 IAM 注册客户端一致。 */
  scope: string
}

/** Tenant Realm：Identity 登录，Token 绑定 Active Tenant。 */
export const tenantRealm: RealmConfig = Object.freeze({
  storageKey: 'forge.tenant.session',
  authPath: '/iam/auth',
  scope: 'tenant',
})

/** Platform Realm：Operator 登录，Token 的 issuer 与 Tenant Realm 不同，只能访问平台路由。 */
export const platformRealm: RealmConfig = Object.freeze({
  storageKey: 'forge.platform.session',
  authPath: '/iam/platform/auth',
  scope: 'platform',
})

/** 两个 Realm 会话共有的凭证字段。 */
export interface RealmSession {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
}

/** Tenant Realm 登录返回的会话；凭证只保存在当前浏览器标签页。 */
export interface TenantSession extends RealmSession {
  identityName: string
  /** 当前 Active Tenant；切换租户后随新 Token 一起变化。 */
  tenantId: string
  tenantName: string
  /** 当前 Token 绑定的 Membership。 */
  membershipId: string
}

/** Platform Realm 登录返回的会话。 */
export interface PlatformSession extends RealmSession {
  operatorName: string
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

/** Platform Realm 会话变化时的回调；`null` 表示已无会话。 */
export type PlatformSessionListener = (session: PlatformSession | null) => void

/** Shell 创建并提供的客户端类型，业务组件复用同一刷新队列。 */
export type TenantClient = ReturnType<typeof createTenantClient>

/** platform-console 创建并提供的客户端类型。 */
export type PlatformClient = ReturnType<typeof createPlatformClient>

/** Shell 通过 Vue provide 提供客户端，Sub-App 通过同一个 SDK 单例中的 key 注入。 */
export const tenantClientKey: InjectionKey<TenantClient> = Symbol('forge.tenant.client')

/** platform-console 通过 Vue provide 提供 Platform Realm 客户端。 */
export const platformClientKey: InjectionKey<PlatformClient> = Symbol('forge.platform.client')

/** 创建同源 Tenant Realm 客户端；Shell 持有一个实例，Sub-App 共享该实例。 */
export function createTenantClient(storage: Storage) {
  const core = createRealmClient<TenantSession>(tenantRealm, storage)

  /** 校验账号密码并建立 IAM 登录会话，按可进入的 Membership 数量决定自动进入、选择租户或无租户。 */
  async function login(account: string, password: string): Promise<TenantLoginResult> {
    const settings = await core.authenticate<{ memberships?: TenantMembership[] }>(
      account,
      password,
      (value) => (value.memberships ?? []).length > 0,
    )
    const memberships = settings.memberships ?? []
    if (memberships.length === 0) return { status: 'noTenant' }
    if (memberships.length > 1) return { status: 'selectTenant', memberships }
    return { status: 'signedIn', session: await selectTenant(memberships[0]!.membershipId) }
  }

  /** 以 `login` 建立的 IAM 登录会话进入指定 Membership 所在租户，签发绑定该租户的 Token。 */
  function selectTenant(membershipId: string): Promise<TenantSession> {
    return core.authorize({ membershipId })
  }

  /** 列出当前身份可进入的有效 Membership，供租户切换器使用。 */
  async function memberships(): Promise<TenantMembership[]> {
    return core.api<TenantMembership[]>(`${tenantRealm.authPath}/memberships`)
  }

  /**
   * 用当前 Refresh Token 换发绑定目标 Membership 的新会话；被拒绝时保留原会话。
   * 成功后切换前发出的请求一律以 `auth.sessionChanged` 失败，调用方应清空租户相关状态并重新加载。
   */
  function switchTenant(membershipId: string): Promise<TenantSession> {
    return core.rebind(`${tenantRealm.authPath}/switch`, { membershipId })
  }

  return {
    api: core.api,
    page: core.page,
    login,
    selectTenant,
    memberships,
    switchTenant,
    onSessionChange: core.onSessionChange,
    logout: core.logout,
    refresh: core.refresh,
    request: core.request,
    session: core.session,
    clear: core.clear,
  }
}

/** 创建同源 Platform Realm 客户端；platform-console 持有一个实例。 */
export function createPlatformClient(storage: Storage) {
  const core = createRealmClient<PlatformSession>(platformRealm, storage)

  /** 校验 Operator 账号密码后经标准授权码 + PKCE 流程签发 Platform Realm Token。 */
  async function login(account: string, password: string): Promise<PlatformSession> {
    await core.authenticate(account, password, () => true)
    return core.authorize({})
  }

  return {
    api: core.api,
    page: core.page,
    login,
    onSessionChange: core.onSessionChange,
    logout: core.logout,
    refresh: core.refresh,
    request: core.request,
    session: core.session,
    clear: core.clear,
  }
}

/** 两个 Realm 共用的会话、授权码 + PKCE、刷新与请求逻辑；接口位置与存储键来自 Realm 配置。 */
function createRealmClient<S extends RealmSession>(realm: RealmConfig, storage: Storage) {
  // 刷新与切换都会轮换同一个 Refresh Token，必须串行，共用这一个队列。
  let pendingRotation: Promise<S> | undefined
  let generation = 0
  // 登录会话已建立、尚未签发 Token 时的授权参数；只在同一 generation 内有效。
  let authorization: { clientId: string; redirectUri: string; generation: number } | undefined
  const listeners = new Set<(session: S | null) => void>()

  function store(value: S | null) {
    if (value) storage.setItem(realm.storageKey, JSON.stringify(value))
    else storage.removeItem(realm.storageKey)
    for (const listener of listeners) listener(value)
  }

  /** 订阅会话变化，返回取消订阅函数。 */
  function onSessionChange(listener: (session: S | null) => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }

  function session(): S | null {
    try {
      const value = JSON.parse(storage.getItem(realm.storageKey) ?? 'null') as S | null
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
    storage.removeItem(realm.storageKey)
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
      const response = await fetch(`${realm.authPath}/logout`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
      await readEnvelope(response)
    } catch {
      throw new Error('auth.logoutFailed')
    }
  }

  async function exchange(path: string, body: object): Promise<S> {
    const started = generation
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    // 失败以服务端错误码抛出：auth.failed、auth.tenantAccessDenied 表示凭证被拒绝，
    // system.unavailable 等故障码与非信封响应不代表凭证失效，由调用方区分处理。
    const value = (await readEnvelope<S>(response)).data as S
    if (started !== generation) throw new Error('auth.sessionChanged')
    store(value)
    return value
  }

  /**
   * 校验账号密码并建立 IAM 登录会话，返回 IAM 的登录结果。`canAuthorize` 为 false 时不保留授权参数，
   * 之后的 `authorize` 以 `auth.failed` 失败。
   */
  async function authenticate<T extends object>(
    account: string,
    password: string,
    canAuthorize: (settings: T) => boolean,
  ): Promise<T> {
    clear()
    const started = generation
    const authenticated = await fetch(`${realm.authPath}/login`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account, password }),
    })
    const settings = (
      await readEnvelope<T & { clientId: string; redirectUri: string }>(authenticated)
    ).data!
    // 回调必须与 Shell 同源；fetch 跟随标准授权重定向，不执行回调 HTML。
    if (new URL(settings.redirectUri).origin !== location.origin)
      throw new Error('auth.invalidRedirect')
    if (started !== generation) throw new Error('auth.sessionChanged')
    if (canAuthorize(settings))
      authorization = { clientId: settings.clientId, redirectUri: settings.redirectUri, generation }
    return settings
  }

  /** 以 `authenticate` 建立的 IAM 登录会话经授权码 + PKCE 签发 Token；`extra` 随换码请求提交。 */
  async function authorize(extra: Record<string, string>): Promise<S> {
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
      scope: realm.scope,
      state,
      code_challenge_method: 'S256',
      code_challenge: challenge,
    })
    // 两个 Realm 共用 IAM 的标准授权端点，以 client_id 区分。
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
    const value = await exchange(`${realm.authPath}/exchange`, {
      code,
      codeVerifier: verifier,
      ...extra,
    })
    authorization = undefined
    return value
  }

  /**
   * 用当前 Refresh Token 换发重新绑定上下文的新会话（如切换租户）；被拒绝时保留原会话。
   * 成功后提升会话代次，之前发出的请求一律以 `auth.sessionChanged` 失败。
   */
  function rebind(path: string, body: object): Promise<S> {
    const previous = pendingRotation ?? Promise.resolve(undefined)
    const operation = previous
      .catch(() => undefined)
      .then(async () => {
        const value = session()
        if (!value) throw new Error('auth.failed')
        const started = generation
        const response = await fetch(path, {
          method: 'POST',
          credentials: 'omit',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: value.refreshToken, ...body }),
        })
        if (started !== generation) throw new Error('auth.sessionChanged')
        let next: S
        try {
          next = (await readEnvelope<S>(response)).data as S
        } catch (error) {
          // 只有刷新凭证被拒绝才说明会话已失效；租户被拒绝、网关或服务端故障时保留原会话以便重试。
          if (error instanceof ApiError && error.code === 'auth.failed' && started === generation)
            clear()
          throw error
        }
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

  /**
   * 轮换刷新凭证；并发调用共用进行中的一次。凭证被拒绝（刷新接口返回 401，或 Membership、租户已停用）时清除会话，
   * 服务端或网络故障以原错误失败并保留会话。
   */
  function refresh(): Promise<S> {
    if (pendingRotation) return pendingRotation
    const value = session()
    if (!value) return Promise.reject(new Error('auth.failed'))
    const started = generation
    const operation = exchange(`${realm.authPath}/refresh`, { refreshToken: value.refreshToken })
      .catch((error: unknown) => {
        // 只有刷新凭证被拒绝才登出：刷新接口的任何 401（不论响应是否为信封）或租户被拒绝；
        // 5xx、503 与网络故障保留会话，恢复后可继续刷新。
        const rejected =
          error instanceof ApiError &&
          (error.httpStatus === 401 ||
            ['auth.failed', 'auth.tenantAccessDenied'].includes(error.code))
        if (rejected && started === generation) clear()
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

  /** 经 `request` 调用信封接口，成功返回 `data`，失败抛出 `ApiError`。 */
  async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
    return (await readEnvelope<T>(await request(path, init))).data as T
  }

  /** 经 `request` 调用分页接口，返回条目与 `meta.page`，失败抛出 `ApiError`。 */
  async function page<T>(path: string, init: RequestInit = {}): Promise<Page<T>> {
    return readPage<T>(await request(path, init))
  }

  return {
    api,
    page,
    authenticate,
    authorize,
    rebind,
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
