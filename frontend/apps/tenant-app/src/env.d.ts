/** Shell 提供给 Sub-App 的共享依赖版本，键为包名；构建时由 federation.shared.ts 注入。 */
declare const __SHARED_VERSIONS__: Record<string, string>

interface ImportMetaEnv {
  /** 构建时设为 `true` 注册 `/__diagnostics` 技术验证诊断页，E2E 构建使用。 */
  readonly VITE_FORGE_DIAGNOSTICS?: string
}
