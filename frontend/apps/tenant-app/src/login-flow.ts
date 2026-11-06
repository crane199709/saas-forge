import { ApiError, type TenantClient, type TenantMembership } from '@crane199709/sdk'
import { reactive } from 'vue'
import type { ErrorTip } from './error-tip'

/** 登录、选择租户与退出之间跨页面传递的状态。只存于内存，页面刷新后随之丢失。 */
export const loginFlow = reactive({
  /** 账号已校验、尚未选定租户时的可选 Membership。 */
  memberships: null as TenantMembership[] | null,
  /** 回到登录页时要展示的错误，如选择租户时登录会话已失效。 */
  error: null as ErrorTip | null,
  /** IAM 退出失败：本地凭证已清除，但浏览器仍可能保持 IAM 登录会话，登录页提供重试。 */
  logoutFailed: false,
})

/**
 * 登录后回跳的地址：只接受站内路径，防止开放重定向。`//host` 与 `/\host` 都会被浏览器当作站外地址。
 */
export function safeRedirect(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/')) return '/'
  return value.startsWith('//') || value.startsWith('/\\') ? '/' : value
}

/**
 * 当场绑定（或绑定后重试进入）失败的原因是否只能靠完整登录解决：Token 与登录会话都已失效；身份已在别处绑定，
 * 当前授权却没有完成 MFA，只有重新登录并完成 MFA 才能记下；绑定后重试仍被拒绝。
 */
export function needsFullLogin(cause: unknown): boolean {
  if (cause instanceof ApiError) {
    return (
      cause.httpStatus === 401 ||
      ['auth.failed', 'auth.mfaRequired', 'mfa.alreadyBound'].includes(cause.code)
    )
  }
  return cause instanceof Error && cause.message === 'auth.failed'
}

/** 退出登录。本地凭证先被清除，IAM 退出失败时记入 `logoutFailed`，由登录页提示重试。 */
export async function signOut(client: Pick<TenantClient, 'logout'>) {
  loginFlow.logoutFailed = false
  try {
    await client.logout()
  } catch {
    loginFlow.logoutFailed = true
  }
}
