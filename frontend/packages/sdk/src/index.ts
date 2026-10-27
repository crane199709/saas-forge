/** 技术验证阶段的 SDK 模块身份，用引用相等性发现重复加载。 */
export const sdkRuntime = Object.freeze({ version: '0.1.0' })

export { ApiError } from './api'
export type { ApiResponse, FieldError, Page, PageMeta } from './api'
export {
  createPlatformClient,
  createTenantClient,
  platformClientKey,
  platformRealm,
  tenantClientKey,
  tenantRealm,
} from './auth'
export type {
  PlatformClient,
  PlatformSession,
  PlatformSessionListener,
  RealmConfig,
  RealmSession,
  TenantClient,
  TenantLoginResult,
  TenantMembership,
  TenantSession,
  TenantSessionListener,
} from './auth'
