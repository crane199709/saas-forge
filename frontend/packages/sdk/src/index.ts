/** 技术验证阶段的 SDK 模块身份，用引用相等性发现重复加载。 */
export const sdkRuntime = Object.freeze({ version: '0.1.0' })

export {
  accessControlKey,
  createAccessControl,
  subAppContainerName,
  useAccessControl,
} from './access'
export type { AccessControl, MenuNode, SubAppEntry, TenantAccess } from './access'
export { ApiError } from './api'
export type { ApiResponse, FieldError, Page, PageMeta } from './api'
export {
  createPlatformClient,
  createTenantClient,
  platformClientKey,
  platformRealm,
  tenantClientKey,
  tenantRealm,
  useTenantClient,
} from './auth'
export type {
  CodeDelivery,
  InvitationAcceptance,
  InvitationPreview,
  PlatformClient,
  PlatformSession,
  PlatformSessionListener,
  RealmConfig,
  RealmSession,
  RegistrationInput,
  TenantClient,
  TenantLoginResult,
  TenantMembership,
  TenantSession,
  TenantSessionListener,
} from './auth'
