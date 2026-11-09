# 存储用途最终验收

日期：2026-11-09。集成分支：`codex/storage-purpose-integration`。审查基点：`47fedbdb064bd7e52154d3b1af3462a67b3c5e63`，实现提交：`aacdbe3`、`1628869`。

## 实现结果

- 工单 01、02 均为 resolved。`StoragePurpose` 以 Java 常量声明编码、扩展名白名单、大小与下载时长，构造时拒绝非法规则。SDK 上传、流式写入和下载均强制传入用途。
- 对象记录持久化用途；同租户用途错误返回 `storage.purposeMismatch`，跨租户仍返回 404。扩展名大小写不敏感，不符合白名单和超限分别返回对应错误码，拒绝不占用配额。
- IO 源文件为 xlsx/20 MiB，结果为 xlsx/100 MiB；附件为 txt/pdf/xlsx/png/jpg/jpeg、20 MiB；均为 5 分钟下载 URL。导入在提交任务前核对用途，错误对象不会进入清理路径；附件在绑定前核对用途。
- 正式 V14 回填旧对象为 `unspecified`，随后移除数据库默认值。测试在 V14 前插入旧格式记录，实际验证回填后保留对象并拒绝错误用途下载。旧对象必须根据可信业务关联显式归类，不自动按扩展名猜测。
- 删除无用途上传/写入/下载重载和旧全局下载 TTL。脚手架保持按需启用存储，指南和 CHANGELOG 已同步；前端既有 userTip 展示满足需求，没有产品 UI 源码改动。

## 验证

工作树：`/Users/liuhuan/.codex/worktrees/storage-purpose-integration/saas-forge-v2`。

| 检查 | 结果 |
| --- | --- |
| 当前源码 `examples/demo-service -am install -DskipTests` | 通过；同步全部相关 SDK，含 Spotless |
| storage 模块全量 | 3 项通过，0 失败/错误/跳过 |
| IO 模块全量 | 1 项通过，0 失败/错误/跳过 |
| demo 模块全量 | 95 项通过，0 失败/错误/跳过；含 ObjectStorage 36 项、迁移、中文注释、架构及既有业务回归 |
| `forge-service-archetype clean verify -Parchetype-it` | 通过，实际生成工程并验证 |
| 前端 `pnpm build` | 通过；现有大 chunk 提示不影响构建 |
| 前端 `pnpm typecheck`、`pnpm format:check` | 通过，包含新增浏览器测试的类型与格式 |
| Playwright | 4 项通过；附件扩展名/大小拒绝、导入服务端大小拒绝、非 xlsx 客户端拒绝 |
| `git diff --check` | 通过 |

后端全量命令：

```sh
cd backend
./mvnw -o -pl examples/demo-service -am install -DskipTests
./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io,examples/demo-service test
./mvnw -o -pl forge-service-archetype clean verify -Parchetype-it
```

## 浏览器 QA

Browser plugin not available，使用仓库 Playwright。Chromium，1280×900，Shell `http://127.0.0.1:5274`，demo remote `http://127.0.0.1:5275/remoteEntry.js`。没有停止或复用用户已有开发服务。

首次运行因临时 Shell 端口不在 demo preview 的默认 CORS 允许来源中，子应用未能加载；仅调整仓库外临时 Vite 配置，允许本次本地测试来源后，重跑全部 4 项通过。没有修改产品 CORS 配置或放宽生产边界。

| 检查 | 结论 |
| --- | --- |
| 页面身份 | 租户端标题及附件/订单 URL 正确 |
| 页面内容 | Shell、动态 demo remote 和业务表单正常显示，无空白页 |
| 框架错误覆盖层 | 未出现 |
| 控制台 | 无未预期 pageerror/error/warning；仅排除本用例主动制造的 HTTP 400 资源日志 |
| 交互 | 选择文件→上传/导入→显示 userTip；拒绝后按钮恢复，PUT 和业务确认请求数为 0 |
| 截图 | 已人工查看附件扩展名拒绝及导入大小拒绝截图，提示清晰可见，无布局遮挡 |

运行命令（临时配置位于仓库外）：

```sh
cd frontend
FORGE_STORAGE_REMOTE_ENTRY=http://127.0.0.1:5275/remoteEntry.js \
  pnpm exec playwright test --config /private/tmp/storage-purpose-playwright.config.mts
```

浏览器在公开 HTTP 边界模拟错误信封，只证明 UI 文案传播和后续请求停止；真实校验、对象存储、配额、迁移及租户隔离由 PostgreSQL/Redis/S3 容器集成测试覆盖。本次未运行完整真实网关端到端部署，也未覆盖移动端视口。

## Standards

规范硬违规：0 项。判断型气味：0 项。已对照 AGENTS、编码规范、领域文档、GLOSSARY、相关 ADR 和接入指南审查完整三点差异；用途校验集中在 ObjectStorage，IO 共用常量，复制升级 SQL 符合服务迁移约定。

## Spec

发现：0 项。核查了强制用途、错误码、下载 TTL、旧行回填、跨租户不泄露、导入前拒绝且保留对象、附件绑定下载、全部调用迁移、脚手架按需接入及前端提示；未发现缺失、范围扩大或错误实现。

两轴合计：Standards 0 项，Spec 0 项；均无待修复问题。

## 本地证据

日志位于 `/private/tmp/storage-purpose-final-install.log`、`storage-purpose-backend-full.log`、`storage-purpose-archetype-verify.log`、`storage-purpose-frontend-build.log`、`storage-purpose-frontend-typecheck.log`、`storage-purpose-frontend-format.log`、`storage-purpose-playwright-final.log`。审查原文为 `/private/tmp/storage-purpose-review-standards.md`、`storage-purpose-review-spec.md`。这些是本机临时证据，不属于仓库交付文件。

截图：

![附件扩展名拒绝](/private/tmp/storage-purpose-attachment-rejected.png)
![导入大小拒绝](/private/tmp/storage-purpose-import-large.xlsx.png)
