# 版本化 API Client

Issue #203 的 Client 发布渠道为 npm 官方公开仓库 `https://registry.npmjs.org/`，包名为 `@crane199709/saas-forge-api-client`。发布账号 `crane199709` 已于 2026-09-15 通过 `npm whoami` 核实。普通消费者安装公开包无需 npm 凭据。旧 `@saas-forge/api-client` 是保留的仓库工作区入口，不能作为新仓库的制品依赖。

## 后端生成与发布

维护目录：`saas-forge-contracts/saas-forge-openapi-contracts/typescript-client/`。

在该目录执行 `npm ci`、`npm run build`。生成使用根 POM 管理的 OpenAPI Generator，由正式 `v1.yaml` 和 `common.yaml` 生成 ESM JavaScript 与声明文件；输出位于模块自己的 `target/` 和 `dist/`，不读取或安装旧 Console。生成使用 JDK 17 和根 Maven Wrapper；消费者不需要它们。

包无运行时依赖、无安装生命周期脚本；`exports` 指向编译后的 JavaScript 和 `.d.ts`。`contract-source.json` 记录包名、版本、后端提交、工作区是否有未提交输入、契约、POM、编译配置、锁文件、打包脚本与许可证的 SHA-256，以及生成器版本。包只包含编译制品、来源清单和 Apache-2.0 许可证。

发布流程：修改明确的 SemVer 版本并同步 `package-lock.json`，完成制品导入、类型和受影响契约验证，审查并提交。`npm login --registry=https://registry.npmjs.org` 后在维护目录执行 `npm publish`。发布前钩子拒绝有未提交修改的仓库，打包时重新生成。2FA 在发布者自己的终端或 npm 网页完成，不把 OTP、Token 写入仓库、前端环境或命令记录。当前尚未配置 npm trusted publisher，不声称已有自动发布流水线。

首次发布以本地交互身份执行。以后若启用 GitHub Actions OIDC，须先在 npm 包设置绑定具体仓库及工作流，并核实权限；不能仅提交 YAML 就声称发布身份已经可用。

## 消费与独立升级

新前端从 npm 官方 registry 安装精确版本，提交 `package.json` 与含完整性摘要的 `pnpm-lock.yaml`。使用 `pnpm install --frozen-lockfile`；不得使用 `file:`、`link:`、兄弟目录、子模块或安装时生成替代发布包。在前端 `.npmrc` 为自有 scope 指定上述官方 registry，显式升级使用 `pnpm add --save-exact @crane199709/saas-forge-api-client@<版本>`，随后重新执行类型、构建和真实 Gateway 页面验收。

后端先交付兼容运行实现和对应 Client，前端再显式升级；不要求两个仓库同时发布。已记录支持的后端/Client/前端组合在兼容更新后仍须成立。破坏性 operation 或模型变化使用显式协议版本及 Client major 版本，不能覆盖已发布 npm 版本或修改历史契约基线。Client 包版本与后端版本独立；相同契约可以发布打包修订版本。

本票使用现有正式 `getJwks` 验证匿名公开连接，不发布 #202 尚未实现的统一登录 v2。读取公钥成功仅证明公开读取链路，不代表登录、Cookie 或受保护业务已通过。JWKS 仅对既有受控 Console Origin 开放跨域读取，不携带凭据；未增加任意 Origin 白名单。

## 验收记录

真实版本与执行结果见 [Issue #203 验收记录](acceptance/issue-203-versioned-client.md)。发布包、本地打包、模拟请求和真实浏览器结果必须分别标记，不互相代替。

## 制品修订

- `0.1.0`：首次独立 ESM 和类型发布。
- `0.1.1`：来源清单新增编译配置、锁文件、打包脚本及许可证摘要；正式 v1 契约和 operation 不变，用于验证显式补丁升级。
