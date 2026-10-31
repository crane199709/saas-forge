import vue from '@vitejs/plugin-vue'
import { federation } from '@module-federation/vite'
import { mergeConfig, type ConfigEnv, type UserConfig, type UserConfigFnObject } from 'vite'
import { sharedDependencies } from '../../../federation.shared.ts'

/** Sub-App 构建配置的选项。 */
export interface SubAppOptions {
  /** 应用编码，与 App Registry、菜单节点的 `app` 一致；Shell 将路由挂在 `/apps/<appCode>` 下。 */
  appCode: string
  /** 本地开发与 preview 端口，各 Sub-App 不同。 */
  port: number
  /** 加载本 Sub-App 的 Shell 源，preview 与部署时须允许其跨源请求入口与资源。 */
  shellOrigins?: string[]
  /** 独立开发时宿主代理的接口前缀，转发到本地网关，如 `['/demo']`；`/iam` 总是代理，供宿主登录。 */
  apiPrefixes?: string[]
  /** 网关地址，默认 `http://localhost:8080`。 */
  gateway?: string
}

/**
 * Module Federation 容器名，须与 `@crane199709/sdk` 的 `subAppContainerName` 一致：Shell 按应用编码注册远程模块。
 * 构建配置在 Node 中加载，不引入运行时 SDK。
 */
export function containerName(appCode: string): string {
  return `forge_app_${appCode.replaceAll('-', '_')}`
}

/**
 * Sub-App 的 Vite 配置，按工单 02 的接入契约生成。
 *
 * - `vite`（开发）：不启用 Module Federation，`index.html` 经 `@crane199709/sub-app-kit/dev-host` 提供开发用宿主，
 *   无需启动 Shell App 工程，Vue 的 HMR 照常生效。
 * - `vite build` / `vite preview`：暴露 `./sub-app` 的 ESM `remoteEntry.js`，共享依赖 singleton 且只消费 Shell 提供的实例
 *   （`import: false`，`loaded-first`），资源使用相对 `base`，preview 允许 Shell 源跨源访问。
 *
 * @param override 按命令与模式追加的配置，与生成的配置合并
 */
export function defineSubAppConfig(
  options: SubAppOptions,
  override: (env: ConfigEnv) => UserConfig = () => ({}),
): UserConfigFnObject {
  const gateway = options.gateway ?? 'http://localhost:8080'
  const origins = options.shellOrigins ?? ['http://127.0.0.1:5174', 'http://localhost:5174']
  return (env) =>
    mergeConfig(
      {
        base: './',
        plugins: [
          vue(),
          ...(env.command === 'build' || env.isPreview
            ? [
                federation({
                  name: containerName(options.appCode),
                  filename: 'remoteEntry.js',
                  exposes: { './sub-app': './src/sub-app.ts' },
                  shared: sharedDependencies(false),
                  shareStrategy: 'loaded-first',
                  dts: false,
                }),
              ]
            : []),
        ],
        server: {
          port: options.port,
          strictPort: true,
          proxy: Object.fromEntries(
            ['/iam', ...(options.apiPrefixes ?? [])].map((prefix) => [prefix, gateway]),
          ),
        },
        preview: { port: options.port, strictPort: true, cors: { origin: origins } },
      } satisfies UserConfig,
      override(env),
    )
}
