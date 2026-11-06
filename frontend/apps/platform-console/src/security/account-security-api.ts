import type { PlatformClient, RecoveryCodes } from '@crane199709/sdk'

/** 开始绑定或更换后返回的 `otpauth://` URI，供渲染二维码。 */
export interface BindingStart {
  otpauthUri: string
}

/** 当前 Operator 的自助安全接口，凭 Platform Access Token 调用。 */
export function createAccountSecurityApi(client: Pick<PlatformClient, 'api'>) {
  const base = '/iam/platform/auth'
  const post = <T>(path: string, body: object) =>
    client.api<T>(`${base}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })

  return {
    // 改密码后当前 Access Token 随之被拒，下次请求遇到 401 时 SDK 自动刷新并继续使用。
    changePassword: (currentPassword: string, newPassword: string) =>
      post<null>('/password/change', { currentPassword, newPassword }),
    /** 已绑定时以 `mfa.alreadyBound` 失败。 */
    startBinding: () => post<BindingStart>('/mfa/bind/start', {}),
    startReplacement: (code: string) => post<BindingStart>('/mfa/replace/start', { code }),
    confirmBinding: (code: string) => post<RecoveryCodes>('/mfa/bind/confirm', { code }),
    regenerateRecoveryCodes: (code: string) =>
      post<RecoveryCodes>('/mfa/recovery-codes/regenerate', { code }),
  }
}
