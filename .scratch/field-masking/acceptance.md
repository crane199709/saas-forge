# 字段脱敏集成验收

Status: resolved

验收日期：2026-11-12。集成分支：`codex/field-masking`；固定基线：`301237d`；最终实现及审查修复：`33c9ce9`。SDK 保持未发布的 `0.2.0-SNAPSHOT`，demo Resource Manifest 已从 7 升为 8。

## 实现结果

五张工单均已完成并标记 `resolved`，变更全部位于同一集成分支：

| 工单 | 结果 | 主要提交 |
|---|---|---|
| [01 Manifest 声明](issues/01-manifest-sensitive-fields.md) | 敏感字段契约、注解扫描、启动/上报校验及模板同步 | `195ffe7` |
| [02 统一引擎](issues/02-masking-engine.md) | 九种固定策略、参数校验、预览、短值与未改变结果失败 | `bd28f03` |
| [03 IAM 与页面](issues/03-iam-data-security.md) | 分级/规则、字段登记、租户覆盖、引用删除保护、权限/RLS/审计及两端页面 | `f11de62`、`8071812`、`2597b6c` |
| [04 导出与日志](issues/04-export-and-log-masking.md) | 写单元格前统一脱敏、零行预检、L4 限制及 Logback 失败关闭 | `4460b5a` |
| [05 在线与 demo](issues/05-online-and-acceptance.md) | 嵌套/集合/null、完整响应失败、三通道示例、独立原文操作与架构约束 | `fa489c4` |
| [审查修复](review.md) | 保留实际转换器上下文、模型/SQL/import 规范及测试身份隔离 | `33c9ce9` |

租户端与运营端均有 `/data-security` 页面。租户只能选择规则，平台维护固定策略规则与分级默认规则。原文查看仅由专用操作通过独立 `masking:viewRaw` 权限和审计提供，序列化器没有角色放行开关。

## 最终验证

后端在 `33c9ce9` 的冻结源码执行完整 reactor 验证：

```sh
cd backend
./mvnw -o verify
```

**BUILD SUCCESS：1,124 项测试，0 失败、0 错误、0 跳过。** 所有 SDK、Gateway、IAM、Platform、demo-service 与 forge-service-archetype 均成功，包含架构、表注释与 Spotless 检查。最终日志：`/private/tmp/field-masking-backend-final-verify.log`，耗时 5 分 7 秒。

前端与脚手架验证：

| 验证 | 结果 |
|---|---|
| tenant-app 全量组件测试 | 37 文件、235 项通过 |
| platform-console 全量组件测试 | 18 文件、100 项通过 |
| 两端 typecheck、构建、tools typecheck | 通过 |
| stylelint、Prettier | 通过 |
| macOS Chromium 视觉比较 | 30/30 通过，1440×900 与 1024×768 |
| 截图设计走查 | 租户 12 张、运营 18 张逐张查看；1024 横向滚动可达性断言通过 |
| sub-app-kit 生成测试 | 2 项通过 |

浏览器用仓库 Playwright，当前会话未提供 Browser 插件。测试 preview 地址为 `http://127.0.0.1:5184/data-security` 与 `http://127.0.0.1:5183/data-security`，由测试临时启动并退出；页面身份、非空、无框架覆盖层、console 健康及操作状态均验证。浏览器接口通过 `page.route` 模拟；真实 IAM HTTP 与公开 SDK 策略端口已单独交叉验证。

前端命令为 `pnpm --filter @forge/tenant-app --filter @forge/platform-console test`、两端 `typecheck`、`pnpm lint:style`、`pnpm format:check`、`pnpm typecheck:tools`、`pnpm test:visual --grep=data-security`。完整记录及日志指针：`/private/tmp/field-masking-03-ui-notes.md`；30 张基线位于 `frontend/e2e/visual/__screenshots__/data-security-*.png`。

## 关键行为证据

- 真实 PostgreSQL、Redis、S3 和 HTTP 导出任务验证工作簿单元格、未声明列、零行缺规则、L4 禁止、执行失败无结果/下载入口，以及一次任务内快照一致。成功和失败的 `export.run` 审计均不写可能带原文的自由文本查询摘要。
- Logback 验证真实 Boot SPI 与最终 encoder/appender 输出，覆盖消息别名、结构化参数、显式包装、异步事件捕获的租户 MDC、冷启动/过期/规则缺失/不可用和关闭后安全输出。
- 真实 HTTP 验证嵌套、集合、null、单次响应快照，以及 64KB 响应末尾缺字段、缺规则、执行未改变或 IAM 不可用时完整返回 500，无部分 JSON 或原文。
- 原文操作验证独立权限与成功/失败审计；IAM 验证租户隔离、Realm 拒绝、所有租户规则引用保护、并发覆盖/Manifest 上报与删除竞态、损坏引用启动失败。
- 六项序列化兼容回归保留 view、动态 filter、多态声明类型、专用 mapper/媒体类型、writer 定制与 converter class 匹配；二进制、StreamingResponseBody 和 Callable JSON 路径亦有验证。

## 审查结论

[双轴审查记录](review.md) 的 3 项 Standards P3 与 1 项 Spec P1 均在 `33c9ce9` 修复，并由原审查代理独立复核关闭。首轮全量暴露的 demo 权限夹具串扰也已修复，完整 `ObjectStorageTest` 51 项及最终全量继续通过。生产授权与缓存语义未因测试修复改变。

## 适用边界

- 在线原子失败保障针对正常 JSON HTTP converter 输出，完整字节缓冲的内存占用随响应大小增长；文件与流式响应保留既有流程，不纳入该缓冲保障。
- 日志使用独立一秒有效快照并异步刷新；首次、过期或不可用时丢弃敏感值，配置最多一秒收敛。在线与导出没有跨执行策略缓存。
- 日志自动识别覆盖命名参数、唯一短名、显式 `SensitiveLogValue` 和结构化 key/value；任意拼接、无名位置参数、整个 DTO、异常内容或自定义 JSON encoder 需要调用方提供安全参数。
- 未声明字段保持原有序列化和导出行为，敏感字段登记完整性由业务服务负责。本次未进行 Shell、Gateway、IAM、demo 同时部署的完整环境联调。
