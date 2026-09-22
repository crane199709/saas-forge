# 旧 Console 迁出与后端验证边界

日期：2026-09-22。用户明确要求把有效内容迁出到 `~/workspace/coding/backup` 后删除本仓 `consoles/`。本次完成归档与后端依赖解除，不表示旧页面和验收已迁入 `saas-forge-web`，也不表示 #206 或父 #201 已完成。

## 可恢复备份

本机位置：`/Users/liuhuan/workspace/coding/backup/saas-forge-consoles-20260922-b29a863/`。

- `consoles/`：原目录完整移动，包含源代码、测试、文档，以及原有依赖和本机产物。原目录已移除，未创建软链接或让构建读取 backup。
- `tracked-files.sha256.json`：279 个受 Git 跟踪文件的 SHA-256，迁出后全部逐项一致。
- `backend-source-at-export.tar.gz`：提交 `b29a86390916619d79b709752467e3b3d691390f` 的完整已提交源码快照，包含配套脚本、旧 CI、Maven 与 Compose 定义。可在独立临时目录展开追溯，不覆盖当前仓库或新前端 main。
- `legacy-support/`：迁出前配套 CI、部署与旧浏览器脚本按原路径展开，另有 SHA-256 清单。
- 备份目录权限为 700；本机文件不应公开上传。备份不作为通过的产品验收证据。其他环境可从上述 Git 提交恢复同一受版本控制的源文件。

## 当前职责

| 内容 | 当前处置与验证归属 |
| --- | --- |
| OpenAPI v1/v2、服务端生成接口、HTTP 路由与 Java SDK | 继续在后端维护并验证，未修改正式契约或已发布迁移 |
| 默认 TypeScript 代码生成 | 输出到 OpenAPI 模块的 `target/generated-typescript-client`，不再写入旧 UI |
| 固定版本 npm Client | 保留 `saas-forge-contracts/saas-forge-openapi-contracts/typescript-client/` 的独立构建/发布入口 |
| Maven、后端 CI 与 Maven Central 发布 | 保留完整后端单元/集成、契约兼容、覆盖率、架构与迁移检查；移除 pnpm/Playwright/旧页面构建依赖 |
| Tenant 生命周期 Fresh Compose、Nacos 配置/权限/恢复 | 仍由后端 CI 执行，保留隔离环境与失败传播 |
| 本地 HTTPS、证书、域名、Gateway 路由 | 保留基础设施入口；前端应用由独立仓库原生启动，移除旧 Platform/Tenant/all 托管命令 |
| Remote 验收静态资源 | Edge 保留精确 CORS 与只读响应行为，不再挂载旧前端 dist；无外部只读制品时返回 404，待独立验收环境准备接管 |
| 旧 UI、浏览器门禁及页面 Compose | 归档至备份，后端不再执行；新前端按现有正式发布 Client 接入已启动后端 |

## 保留待迁的独有覆盖

下列条目均为**待迁移/待验收**，不能因后端 CI 不再运行而勾选为通过。来源路径均相对于备份 `consoles/`，完整配套入口可从源码快照恢复；由 #206 和各业务迁移票继续承接。

| 覆盖 | 归档来源 | 新归属与完成条件 |
| --- | --- | --- |
| Runtime 状态、并发刷新、多标签、晚到响应、失权与未知结果 | `shared/app-runtime/test/`、`integration-test/session-tabs.test.mjs`、`console-default-realm.test.mjs` | 新前端统一会话 Runtime；适配单 Console，保留负例；#205 已有证据不代替全量旧覆盖迁移 |
| Cookie/Origin/CORS/CSRF、Token 撤销、Redis 故障、四域/Remote 隔离 | `integration-test/browser-session-security.mjs`、`browser-api-security.mjs`、`static-remote.test.mjs` 等 | 前端 Chrome + 后端独立探针，同轮关联版本、Fresh 环境标识与清理责任；#183–#189 要求仍有效 |
| Tenant、Plan、Quota、Subscription、管理员初始化、密码投递、OAuth/Secret 恢复 | `integration-test/*-acceptance.mjs`、`stage2-main-chain.test.mjs` | 各业务迁移票按真实统一 Console 页面完成，不以直接 API 建状态替代页面业务验收 |
| 国际化、格式化、键盘/焦点、无障碍、品牌/主题、视觉与生产错误边界 | `shared/admin/`、`shared/i18n/`、`integration-test/`、视觉配置及基线 | 新前端适配 Soybean 组件，保留有效断言与安全错误显示，不机械复用旧控件定位 |
| 浏览器/前后端环境交接与生命周期验收 | 旧 `scripts/verify-console-authentication-e2e.sh`、本地替换浏览器脚本及 Compose overlays | #206：后端只准备环境/探针，前端只连接已有环境；明确失败传播、保留物与销毁责任 |

## 验证记录

- 通过：迁出前后 279 个受版本控制文件 SHA-256 一致。
- 通过：后端 Node 工具测试 35 项，零失败、零跳过，包括真实 Maven 默认 OpenAPI verify 在不可用 Node 替身下仍成功、HTTPS Edge 拒绝旧应用托管命令、服务替换和初始化边界。
- 通过：Compose 布局校验，6 个独立应用、4 个验收场景，网络/卷隔离、挂载和迁移门禁均通过。
- 通过：从旧目录迁回后端的 22 项 TLS、精确 CORS、静态 Remote、HMR 与 Gateway 转发测试，零失败、零跳过。
- 通过：独立 TypeScript Client 的 `npm run build`（正式 v1/v2 生成及 TypeScript 编译）、19 项 RepositoryStandardsTest、变更 YAML 解析与差异空白检查。未发布新的 npm 版本。
- 待确认：本次提交触发的新后端 CI；前端浏览器覆盖明确退出本仓，不能与此前完整浏览器矩阵混称。
- 未执行：已迁出的旧浏览器产品矩阵；本次明确不修补旧双 Console 页面，也不把归档视为新前端交付完成。
