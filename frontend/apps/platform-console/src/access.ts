import { shallowRef } from 'vue'
import type { NavigationLayout, PlatformClient } from '@crane199709/sdk'

/** iam 下发的当前 Operator 的平台角色、有效权限码与平台控制台的导航布局。 */
export interface PlatformAccess {
  roles: string[]
  permissions: string[]
  /** 没有布局时为 null，导航按 `consolePages` 的顺序显示。 */
  layout?: NavigationLayout | null
}

/** platform-console 按权限码显示的功能，与各服务申报的平台权限码一致。 */
export const permissions = Object.freeze({
  announcementManage: 'platform:announcement:manage',
  tenantRead: 'platform:tenant:read',
  tenantManage: 'platform:tenant:manage',
  planManage: 'platform:plan:manage',
  subAppManage: 'platform:sub-app:manage',
  operatorManage: 'platform:operator:manage',
  roleManage: 'platform:role:manage',
  auditRead: 'platform:audit:read',
  onboardingRead: 'platform:onboarding:read',
  serviceCredentialManage: 'platform:service-credential:manage',
  opsRead: 'platform:ops:read',
  opsManage: 'platform:ops:manage',
  identityMfaReset: 'platform:identity-mfa:reset',
  menuManage: 'platform:menu:manage',
})

const empty: PlatformAccess = Object.freeze({ roles: [], permissions: [], layout: null })

/**
 * 当前 Operator 的平台权限，只用于界面展示，接口仍由服务端按平台角色校验。
 * 停用或角色变更后旧 Token 失效，刷新换发新 Token 时 Shell 重新 `load`，界面随之更新。
 */
export function createPlatformAccess(client: Pick<PlatformClient, 'api'>) {
  const state = shallowRef<PlatformAccess>(empty)
  let generation = 0

  /** 被更晚的 `load` 或 `clear` 取代时不覆盖结果。 */
  async function load() {
    const started = ++generation
    const value = await client.api<PlatformAccess>('/iam/platform/access')
    if (started === generation) state.value = value
  }

  function clear() {
    generation += 1
    state.value = empty
  }

  /** 是否拥有权限码。 */
  const has = (code: string) => state.value.permissions.includes(code)

  /** 是否超级管理员；运营人员的「重置 MFA」只对其开放，没有对应的权限码。 */
  const isSuperAdmin = () => state.value.roles.includes('super-admin')

  return { load, clear, has, isSuperAdmin, state }
}

export type PlatformAccessControl = ReturnType<typeof createPlatformAccess>
