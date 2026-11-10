# 导入确认规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

Import Task 校验通过后立即写入，用户没有机会确认写入规模。

目标：校验通过后等待提交人确认，确认后才写入。

非目标：提交后回滚和覆盖导入（SDK 的 writer 是通用回调，现有导入也没有覆盖语义，等真实需求出现再设计）。

## 2. 流程

上传并校验 → 待确认（显示将写入的行数）或失败（显示 Error Report）→ 提交人确认 → 重新校验 → 写入。

- 只有提交人可以确认，否则返回 `io.confirmForbidden`。状态不符返回 `io.taskNotAwaitingConfirmation`。
- 待确认任务不算中断任务，也不占执行队列。
- 24 小时未确认则过期，并由 `IoMaintenance` 清理源文件。

## 3. 确认时重新校验

确认请求可能落到其他实例，或发生在重启之后。现有校验结果写在本机临时文件，`validate` 和 `writer` 回调也只存在于提交时的内存中，都不能复用。

- 确认接口由 Business Service 提供，再次传入模型和回调（如 `confirmImport(taskId, model, validate, writer)`）。
- 确认后从源文件完整重新校验。失败时生成新的 Error Report，一行不写。
- 这样也能发现校验之后才发生的数据变化。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。

## 实现与最终验收（2026-11-10）

集成分支：`codex/import-confirmation-integration`。实现提交：`384670f`、`ffde9c6`、`61e7b72`。两张票据均已 resolved：[SDK](issues/01-sdk-confirmation.md)、[界面与 demo](issues/02-confirmation-ui.md)。最终结果保留在专用集成工作树。

校验成功后保存行数与源文件并释放执行容量；提交人确认后从源文件完整重新校验，再事务写入。数据库条件更新保证只确认一次；待确认不判中断，24 小时后过期并清理源文件。demo 与界面已接入，错误报告现取下载票据，较早的待确认任务可通过分页找到。复用现有表，无新增迁移。SDK 提交 API 与前端分页 API 的不兼容变化已记入指南和 CHANGELOG。

| 最终检查                                               | 结果                                                                                                               |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| 当前 SDK 安装、Spotless                                | 通过                                                                                                               |
| IO SDK 模块全量                                        | 1 项通过，0 失败/错误/跳过                                                                                         |
| demo-service 模块全量                                  | 105 项通过，0 失败/错误/跳过；含真实 PostgreSQL/Redis/S3、确认、跨实例、租户隔离、重新校验、过期清理和既有业务回归 |
| 全前端构建、typecheck（含 tools）、Prettier、stylelint | 通过                                                                                                               |
| 全部 Vitest + sub-app-kit Node 测试                    | 355 + 6 项通过，0 失败/跳过                                                                                        |
| Playwright                                             | 新确认流程 5 项、原存储上传 4 项、原导入/导出综合回归 1 项通过                                                     |
| macOS 视觉                                             | 8 个场景、10 张 1440×900 / 1024×768 基线通过并逐张查看                                                             |
| git diff --check                                       | 通过                                                                                                               |

后端命令：

```sh
cd backend
./mvnw -o -pl examples/demo-service -am install -DskipTests
./mvnw -o -pl forge-sdk/forge-sdk-io,examples/demo-service test
```

前端命令：

```sh
cd frontend
pnpm build
pnpm typecheck
pnpm format:check
pnpm lint:style
pnpm -r --workspace-concurrency=1 --filter @crane199709/ui --filter @forge/platform-console --filter @forge/tenant-app exec vitest run --maxWorkers=2
pnpm --dir packages/sub-app-kit test
```

默认并发的首次 `pnpm test` 在多个页面出现超时；最终按工作区串行、每套件两个 worker 执行，全部通过，未修改断言或超时设置。完整前端日志为 `/private/tmp/import-confirmation-delivery-{build,tests,typecheck,format,style,kit}.log`；后端日志为 `/private/tmp/import-confirmation-final-backend-full.log`。

规范审查：初次 1 项 P3（队列满 fixture 错误码偏差），已统一为真实 `429 / io.busy`，复核剩余 0 项。规格审查：初次 1 项 P2（较早待确认任务被十个新任务挤出后无确认入口），已通过现有分页接口与导航修复，组件和浏览器覆盖重开、整页刷新及第二页确认，复核剩余 0 项。

Browser plugin not available，使用普通 Playwright；页面地址/标题、有效内容、错误遮罩、console、交互与截图检查通过。浏览器使用 HTTP mock，实际写入和重新校验由真实存储 HTTP 集成测试覆盖；未运行已部署后端的完整浏览器链路。跨实例场景通过停止并重建独立 Spring 上下文验证，未启动独立 JVM。
