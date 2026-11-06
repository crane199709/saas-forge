import { inject, type InjectionKey } from 'vue'
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
  /** 刷新与切换跨标签页互斥用的 Web Locks 名称；同一 Realm 的所有标签页共享一个刷新凭证。 */
  rotationLock: string
}

/** Tenant Realm：Identity 登录，Token 绑定 Active Tenant。 */
export const tenantRealm: RealmConfig = Object.freeze({
  storageKey: 'forge.tenant.session',
  authPath: '/iam/auth',
  scope: 'tenant',
  rotationLock: 'forge.tenant.rotation',
})

/** Platform Realm：Operator 登录，Token 的 issuer 与 Tenant Realm 不同，只能访问平台路由。 */
export const platformRealm: RealmConfig = Object.freeze({
  storageKey: 'forge.platform.session',
  authPath: '/iam/platform/auth',
  scope: 'platform',
  rotationLock: 'forge.platform.rotation',
})

/** 两个 Realm 会话共有的凭证字段。 */
export interface RealmSession {
  accessToken: string
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
}

/** Tenant Realm 登录返回的会话；凭证保存在传入的 Storage 中，宿主传入 `localStorage` 时同一浏览器的标签页共享。 */
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
 * First Factor 校验通过后的结果：唯一的有效 Membership 自动进入；多个时须调用 `selectTenant`；
 * 没有时应展示无租户页，不会签发 Token。已绑定 Authenticator 时为 `mfa`，须调用 `submitMfa`；
 * 唯一的租户要求 MFA 而本次认证未完成 MFA 时为 `mfaBindRequired`，凭登录会话当场绑定后再 `selectTenant` 该 Membership。
 */
export type TenantLoginResult =
  | { status: 'signedIn'; session: TenantSession }
  | { status: 'selectTenant'; memberships: TenantMembership[] }
  | { status: 'noTenant' }
  | { status: 'mfa' }
  | { status: 'mfaBindRequired'; membershipId: string }

/** MFA 步骤提交动态码或 Recovery Code，二者恰好一个。 */
export type MfaFactor = { code: string } | { recoveryCode: string }

/** 只在本次响应中返回的一组 Recovery Code，服务端只存哈希，无法再次取得。 */
export interface RecoveryCodes {
  recoveryCodes: string[]
}

/** 忘记密码校验验证码后的重设凭证；`mfaRequired` 为 true 时重设须同时提交动态码或 Recovery Code。 */
export interface PasswordResetTicket {
  resetToken: string
  mfaRequired: boolean
}

/** 以重设凭证设置新密码；已绑定 Authenticator 时 `code` 与 `recoveryCode` 提供一个。 */
export interface PasswordReset {
  resetToken: string
  password: string
  code?: string
  recoveryCode?: string
}

/**
 * Operator First Factor 校验通过后的结果：无需第二步时直接进入；已绑定 Authenticator 时为 `mfa`，须调用 `submitMfa`；
 * `mfa-required` 且未绑定时为 `bind`，须 `startLoginBinding`、`confirmLoginBinding`，再 `completeLogin`。
 */
export type PlatformLoginResult =
  { status: 'signedIn'; session: PlatformSession } | { status: 'mfa' } | { status: 'bind' }

/** 验证码已受理：有效期与可再次获取前的等待时间，单位秒。 */
export interface CodeDelivery {
  expiresIn: number
  resendIn: number
}

/** 自主注册的输入；`target` 为已获取注册验证码的邮箱或手机号，`displayName` 缺省为账号。 */
export interface RegistrationInput {
  account: string
  password: string
  target: string
  code: string
  displayName?: string
}

/** 接受邀请前展示的信息；`requiresCredentials` 为 true 时被邀请人尚无可登录的 Identity，接受时须设置账号与密码。 */
export interface InvitationPreview {
  tenantName: string
  requiresCredentials: boolean
}

/** 接受邀请的输入；`token` 取自邀请链接，账号、密码与昵称只在需要设置凭证时填写，昵称缺省为账号。 */
export interface InvitationAcceptance {
  token: string
  account?: string
  password?: string
  displayName?: string
}

/** 会话变化的来源；`crossTab` 为 true 表示由同一浏览器的其他标签页改写。 */
export interface SessionChange {
  crossTab: boolean
}

/** 会话变化（登录、刷新、切换、清除）时的回调；`null` 表示已无会话。 */
export type TenantSessionListener = (session: TenantSession | null, change: SessionChange) => void

/** Platform Realm 会话变化时的回调；`null` 表示已无会话。 */
export type PlatformSessionListener = (
  session: PlatformSession | null,
  change: SessionChange,
) => void

/** Shell 创建并提供的客户端类型，业务组件复用同一刷新队列。 */
export type TenantClient = ReturnType<typeof createTenantClient>

/** platform-console 创建并提供的客户端类型。 */
export type PlatformClient = ReturnType<typeof createPlatformClient>

/** Shell 通过 Vue provide 提供客户端，Sub-App 通过同一个 SDK 单例中的 key 注入。 */
export const tenantClientKey: InjectionKey<TenantClient> = Symbol('forge.tenant.client')

/**
 * 在组件中读取 Shell 提供的 Tenant Realm 客户端。Sub-App 经它取得当前 Token 所属租户（`session()`）并调用接口（`api()`，
 * 自动携带 Token 并在过期时刷新），自身不处理登录、Token 刷新与租户切换。
 */
export function useTenantClient(): TenantClient {
  const client = inject(tenantClientKey)
  if (!client) throw new Error('Shell must provide a TenantClient')
  return client
}

/** platform-console 通过 Vue provide 提供 Platform Realm 客户端。 */
export const platformClientKey: InjectionKey<PlatformClient> = Symbol('forge.platform.client')

/**
 * 创建同源 Tenant Realm 客户端；Shell 持有一个实例，Sub-App 共享该实例。传入 `localStorage` 时各标签页共享会话：
 * 其他标签页切换租户、登录或退出时本实例的会话代次递增，在途请求以 `auth.sessionChanged` 失败。
 */
export function createTenantClient(storage: Storage) {
  // 同一 Membership 下的刷新不改变上下文；Membership 变化才是切换。
  const core = createRealmClient<TenantSession>(
    tenantRealm,
    storage,
    (previous, next) => previous.membershipId === next.membershipId,
  )

  /** 校验账号密码并建立 IAM 登录会话，按可进入的 Membership 数量决定自动进入、选择租户或无租户。 */
  function login(account: string, password: string): Promise<TenantLoginResult> {
    return enter('login', { account, password })
  }

  /**
   * 向邮箱或手机号发送登录验证码。目标未注册时服务端响应相同但不发送；
   * 发送间隔内重复请求以 `verification.tooFrequent` 失败。
   */
  function sendLoginCode(target: string): Promise<CodeDelivery> {
    return core.post<CodeDelivery>(`${tenantRealm.authPath}/login/send-code`, { target })
  }

  /** 以邮箱或手机验证码建立 IAM 登录会话，之后与 `login` 相同：自动进入、选择租户或无租户。 */
  function loginWithCode(target: string, code: string): Promise<TenantLoginResult> {
    return enter('code-login', { target, code })
  }

  /** 向待注册的邮箱或手机号发送注册验证码；已被注册时以 `registration.contactTaken` 失败。 */
  function sendRegistrationCode(target: string): Promise<CodeDelivery> {
    return core.post<CodeDelivery>(`${tenantRealm.authPath}/register/send-code`, { target })
  }

  /**
   * 注册全局 Identity，不建立登录会话，也不加入任何租户；调用方随后可用同一账号密码 `login`，
   * 得到 `noTenant`。账号、邮箱或手机号已被占用时以 `registration.*` 失败。
   */
  function register(input: RegistrationInput): Promise<{ identityId: string }> {
    return core.post<{ identityId: string }>(`${tenantRealm.authPath}/register`, input)
  }

  /** 查看邀请；令牌不存在、已过期或已被接受时以 `invitation.invalid` 失败。 */
  function previewInvitation(token: string): Promise<InvitationPreview> {
    return core.post<InvitationPreview>(`${tenantRealm.authPath}/invitations/preview`, { token })
  }

  /**
   * 接受邀请，不建立登录会话；之后用账号密码 `login` 即可进入该租户。需要设置凭证却未填写时以
   * `invitation.credentialsRequired` 失败，账号已被占用时以 `registration.accountTaken` 失败。
   */
  function acceptInvitation(
    input: InvitationAcceptance,
  ): Promise<{ tenantId: string; tenantName: string }> {
    return core.post(`${tenantRealm.authPath}/invitations/accept`, input)
  }

  /**
   * 在待 MFA 状态下提交动态码或 Recovery Code；通过后与 `login` 相同：自动进入、选择租户或无租户。
   * 错误时以 `mfa.codeInvalid`、`mfa.recoveryCodeInvalid` 失败，可重试；待 MFA 已过期时以 `auth.mfaExpired` 失败，须重新登录。
   */
  function submitMfa(factor: MfaFactor): Promise<TenantLoginResult> {
    return enter('login/mfa', factor)
  }

  async function enter(path: string, body: object): Promise<TenantLoginResult> {
    const settings = await core.authenticate<{ memberships?: TenantMembership[] }>(
      path,
      body,
      (value) => (value.memberships ?? []).length > 0,
    )
    if (settings.nextStep === 'mfa') return { status: 'mfa' }
    const memberships = settings.memberships ?? []
    if (memberships.length === 0) return { status: 'noTenant' }
    if (memberships.length > 1) return { status: 'selectTenant', memberships }
    const membershipId = memberships[0]!.membershipId
    try {
      return { status: 'signedIn', session: await selectTenant(membershipId) }
    } catch (error) {
      // 被拒的授权码不能重试；授权参数仍保留，当场绑定后重新 selectTenant 即走一次新的授权码流程。
      if (error instanceof ApiError && error.code === 'auth.mfaRequired')
        return { status: 'mfaBindRequired', membershipId }
      throw error
    }
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

  /** 已登录时以 Access Token 调用，否则凭 IAM 登录会话（被 MFA Requirement 拦下、尚无 Token 时）。 */
  function bindingCall<T>(path: string, body: object): Promise<T> {
    if (core.session()) return core.api<T>(path, jsonPost(body))
    return core.sessionPost<T>(path, body)
  }

  /** 开始绑定 Authenticator，返回供渲染二维码的 `otpauth://` URI；已绑定时以 `mfa.alreadyBound` 失败。 */
  function startMfaBinding(): Promise<{ otpauthUri: string }> {
    return bindingCall(`${tenantRealm.authPath}/mfa/bind/start`, {})
  }

  /**
   * 以首个动态码确认绑定，返回只此一次的 Recovery Code。确认后当前授权（已登录时）或登录会话视为已完成 MFA：
   * 被 `auth.mfaRequired` 拒绝的进入可重新 `selectTenant`，被拒绝的切换可直接重试。
   */
  function confirmMfaBinding(code: string): Promise<RecoveryCodes> {
    return bindingCall(`${tenantRealm.authPath}/mfa/bind/confirm`, { code })
  }

  /** 当前身份是否已绑定 Authenticator。 */
  function mfaStatus(): Promise<{ bound: boolean }> {
    return core.api(`${tenantRealm.authPath}/mfa`)
  }

  /** 验证动态码后重新生成 Recovery Code，旧的一组全部作废。 */
  function regenerateRecoveryCodes(code: string): Promise<RecoveryCodes> {
    return core.api(`${tenantRealm.authPath}/mfa/recovery-codes/regenerate`, jsonPost({ code }))
  }

  /** 验证动态码后解绑 Authenticator，Recovery Code 一并作废。 */
  async function unbindMfa(code: string): Promise<void> {
    await core.api(`${tenantRealm.authPath}/mfa/unbind`, jsonPost({ code }))
  }

  /**
   * 凭原密码修改密码；违反 Password Policy 时以 `password.*` 失败。成功后其他会话失效，
   * 当前 Access Token 随之被拒，下次请求遇到 401 时自动刷新并继续使用。
   */
  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await core.api(
      `${tenantRealm.authPath}/password/change`,
      jsonPost({ currentPassword, newPassword }),
    )
  }

  /** 忘记密码：向已登记的邮箱或手机号发送验证码，目标未注册时响应相同但不发送。 */
  function sendPasswordResetCode(target: string): Promise<CodeDelivery> {
    return core.post<CodeDelivery>(`${tenantRealm.authPath}/password/forgot/send-code`, { target })
  }

  /** 忘记密码：校验验证码，换取一次性的重设凭证。 */
  function verifyPasswordReset(target: string, code: string): Promise<PasswordResetTicket> {
    return core.post<PasswordResetTicket>(`${tenantRealm.authPath}/password/forgot/verify`, {
      target,
      code,
    })
  }

  /** 忘记密码：以重设凭证设置新密码，该身份的全部会话与 Token 随即失效。 */
  async function resetPassword(input: PasswordReset): Promise<void> {
    await core.post(`${tenantRealm.authPath}/password/forgot/reset`, input)
  }

  return {
    api: core.api,
    page: core.page,
    login,
    submitMfa,
    startMfaBinding,
    confirmMfaBinding,
    mfaStatus,
    regenerateRecoveryCodes,
    unbindMfa,
    changePassword,
    sendPasswordResetCode,
    verifyPasswordReset,
    resetPassword,
    sendLoginCode,
    loginWithCode,
    sendRegistrationCode,
    register,
    previewInvitation,
    acceptInvitation,
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
  // 没有租户上下文，其他标签页的刷新始终沿用同一会话。
  const core = createRealmClient<PlatformSession>(platformRealm, storage, () => true)

  /** 校验 Operator 账号密码；无需第二步时经标准授权码 + PKCE 流程签发 Platform Realm Token，否则返回待完成的步骤。 */
  function login(account: string, password: string): Promise<PlatformLoginResult> {
    return enter('login', { account, password })
  }

  /**
   * 在待 MFA 状态下提交动态码或 Recovery Code，通过后签发 Token。错误时以 `mfa.codeInvalid`、`mfa.recoveryCodeInvalid` 失败，可重试；
   * 待 MFA 已过期时以 `auth.mfaExpired` 失败，须重新登录。
   */
  function submitMfa(factor: MfaFactor): Promise<PlatformLoginResult> {
    return enter('login/mfa', factor)
  }

  async function enter(path: string, body: object): Promise<PlatformLoginResult> {
    const settings = await core.authenticate<object>(path, body, () => true)
    if (settings.nextStep === 'mfa' || settings.nextStep === 'bind')
      return { status: settings.nextStep }
    return { status: 'signedIn', session: await core.authorize({}) }
  }

  /** 在必须绑定状态下开始绑定 Authenticator，返回供渲染二维码的 `otpauth://` URI。 */
  function startLoginBinding(): Promise<{ otpauthUri: string }> {
    return core.sessionPost(`${platformRealm.authPath}/login/bind/start`, {})
  }

  /**
   * 在必须绑定状态下以首个动态码确认绑定，IAM 登录会话随之建立。Recovery Code 只此一次，
   * 因此这里不签发 Token：保存后调用 `completeLogin`，避免登录导航使其来不及保存。
   */
  async function confirmLoginBinding(code: string): Promise<RecoveryCodes> {
    const { recoveryCodes } = await core.authenticate<RecoveryCodes>(
      'login/bind/confirm',
      { code },
      () => true,
    )
    return { recoveryCodes }
  }

  /** 以 `confirmLoginBinding` 建立的 IAM 登录会话签发 Platform Realm Token。 */
  function completeLogin(): Promise<PlatformSession> {
    return core.authorize({})
  }

  return {
    api: core.api,
    page: core.page,
    login,
    submitMfa,
    startLoginBinding,
    confirmLoginBinding,
    completeLogin,
    onSessionChange: core.onSessionChange,
    logout: core.logout,
    refresh: core.refresh,
    request: core.request,
    session: core.session,
    clear: core.clear,
  }
}

/**
 * 两个 Realm 共用的会话、授权码 + PKCE、刷新与请求逻辑；接口位置与存储键来自 Realm 配置。
 * `sameContext` 判断其他标签页写入的会话是否只是同一上下文下的刷新。
 */
function createRealmClient<S extends RealmSession>(
  realm: RealmConfig,
  storage: Storage,
  sameContext: (previous: S, next: S) => boolean,
) {
  // 刷新与切换都会轮换同一个 Refresh Token，必须串行，共用这一个队列。
  let pendingRotation: Promise<S> | undefined
  let generation = 0
  // 登录会话已建立、尚未签发 Token 时的授权参数；只在同一 generation 内有效。
  let authorization: { clientId: string; redirectUri: string; generation: number } | undefined
  const listeners = new Set<(session: S | null, change: SessionChange) => void>()

  function notify(value: S | null, crossTab: boolean) {
    for (const listener of listeners) listener(value, { crossTab })
  }

  function store(value: S | null) {
    if (value) storage.setItem(realm.storageKey, JSON.stringify(value))
    else storage.removeItem(realm.storageKey)
    notify(value, false)
  }

  // storage 事件只在其他标签页改写时触发。切换、登录与退出相当于本标签页的 clear 或 rebind，提升代次使在途请求作废；
  // 同一上下文的刷新与本标签页刷新一样不提升代次，在途请求遇到 401 时可用新 Token 重发。
  if (typeof window !== 'undefined') {
    window.addEventListener('storage', (event) => {
      if (event.storageArea !== storage) return
      if (event.key !== null && event.key !== realm.storageKey) return
      const previous = parse(event.oldValue)
      const next = session()
      if (!previous || !next || !sameContext(previous, next)) {
        generation += 1
        pendingRotation = undefined
        authorization = undefined
      }
      notify(next, true)
    })
  }

  /** 订阅会话变化，返回取消订阅函数。 */
  function onSessionChange(
    listener: (session: S | null, change: SessionChange) => void,
  ): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }

  function parse(raw: string | null): S | null {
    try {
      const value = JSON.parse(raw ?? 'null') as S | null
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
    return null
  }

  function session(): S | null {
    const value = parse(storage.getItem(realm.storageKey))
    if (!value) storage.removeItem(realm.storageKey)
    return value
  }

  /**
   * 在跨标签页的轮换锁内执行。各标签页共享同一个一次性刷新凭证，并发轮换时晚到的一方会被 IAM 视为重放并撤销整个授权族。
   */
  async function rotating<T>(task: () => Promise<T>): Promise<T> {
    return await navigator.locks.request(realm.rotationLock, task)
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
   * 以 `realm.authPath` 下的 `path` 接口校验凭证（账号密码或验证码）并建立 IAM 登录会话，返回 IAM 的登录结果。
   * `canAuthorize` 为 false 时不保留授权参数，之后的 `authorize` 以 `auth.failed` 失败。
   */
  async function authenticate<T extends object>(
    path: string,
    credentials: object,
    canAuthorize: (settings: T) => boolean,
  ): Promise<T & { nextStep?: string }> {
    clear()
    const started = generation
    const authenticated = await fetch(`${realm.authPath}/${path}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(credentials),
    })
    const settings = (
      await readEnvelope<T & { nextStep?: string; clientId: string; redirectUri: string }>(
        authenticated,
      )
    ).data!
    // 待 MFA 或待绑定（Platform Realm）：尚未认证，没有授权参数可保留。
    if (settings.nextStep && settings.nextStep !== 'authorize') return settings
    // 回调必须与 Shell 同源；fetch 跟随标准授权重定向，不执行回调 HTML。
    if (new URL(settings.redirectUri).origin !== location.origin)
      throw new Error('auth.invalidRedirect')
    if (started !== generation) throw new Error('auth.sessionChanged')
    if (canAuthorize(settings))
      authorization = { clientId: settings.clientId, redirectUri: settings.redirectUri, generation }
    return settings
  }

  /** 调用不需要凭证与登录会话的匿名信封接口（如发送验证码、注册），失败抛出 `ApiError`。 */
  async function post<T>(path: string, body: object): Promise<T> {
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    return (await readEnvelope<T>(response)).data as T
  }

  /** 凭 IAM 登录会话 Cookie 调用信封接口（如尚无 Token 时当场绑定 Authenticator），失败抛出 `ApiError`。 */
  async function sessionPost<T>(path: string, body: object): Promise<T> {
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    return (await readEnvelope<T>(response)).data as T
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
      .then(() =>
        rotating(async () => {
          // 取得锁后再读存储，使用其他标签页可能刚轮换出的刷新凭证。
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
        }),
      )
      .finally(() => {
        if (pendingRotation === operation) pendingRotation = undefined
      })
    pendingRotation = operation
    return operation
  }

  /**
   * 轮换刷新凭证；本标签页的并发调用共用进行中的一次，各标签页之间经轮换锁串行，其他标签页已轮换时直接采用存储中的新会话。
   * 凭证被拒绝（刷新接口返回 401，或 Membership、租户已停用）时清除会话，服务端或网络故障以原错误失败并保留会话。
   */
  function refresh(): Promise<S> {
    if (pendingRotation) return pendingRotation
    const value = session()
    if (!value) return Promise.reject(new Error('auth.failed'))
    const started = generation
    const operation = rotating(async () => {
      if (started !== generation) throw new Error('auth.sessionChanged')
      const current = session()
      if (!current) throw new Error('auth.failed')
      // 等锁期间其他标签页已轮换：直接采用存储中的新会话，再用旧凭证刷新会被视为重放。
      if (current.refreshToken !== value.refreshToken) return current
      return exchange(`${realm.authPath}/refresh`, { refreshToken: value.refreshToken })
    })
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
    post,
    sessionPost,
    rebind,
    onSessionChange,
    logout,
    refresh,
    request,
    session,
    clear,
  }
}

function jsonPost(body: object): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '')
}
