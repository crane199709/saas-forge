# 01: Manifest 敏感字段声明

**What to build:** 按 [spec](../spec.md) 的“实现决策”，Resource Manifest 新增敏感字段声明，SDK 支持注解扫描生成声明，并在启动与上报时校验。

**Blocked by:** —

**Status:** resolved

- [x] Manifest 支持敏感字段：字段路径、数据分级、可选默认规则；与现有 permissions、dictionaries、dataResources 同一契约
- [x] SDK 提供敏感字段注解，构建时扫描并合并进 Manifest，重复声明或非法字段路径直接失败
- [x] 校验分级只能是 L1–L4；字段路径格式与 Manifest 现有标识符规则一致
- [x] 清单变更需要升 `forge.manifest.version`，接入指南与模板同步说明
- [x] 测试覆盖：合法声明、重复声明、非法分级、非法字段路径

## Comments


## Answer

Resource Manifest 新增可选 `sensitiveFields`，元素为 `ResourceManifest.SensitiveField(path, grade, defaultRule)`；沿用既有构造器，空列表不输出 JSON，旧服务的已存清单保持内容兼容。`path` 按点分普通标识符校验，分级只允许 L1–L4，同一路径重复（即使内容相同）直接拒绝。

SDK 提供 `io.saasforge.sdk.security.SensitiveField`，属性 `path()` / `grade()` / `defaultRule()`（默认空串，生成时转 null），支持 FIELD / RECORD_COMPONENT / METHOD。CLI 与启动使用同一扫描器合并文件声明及字段/getter 注解，按路径排序，不执行业务类静态初始化；record component 传播到 backing field 和 accessor 的同一注解只登记一次。独立字段或文件与注解重复仍失败。`ManifestReporter` 在发送请求前调用统一契约校验；IAM 接收方现有校验随共享契约同步生效，规则存在性由后续工单实现。

规范、接入指南、CHANGELOG、archetype 与 sub-app-kit 模板已同步。新增或调整声明必须升 `forge.manifest.version`；archetype 初始版本仍为 1，现有服务没有敏感字段声明时不虚增版本。后续 demo 加入实际声明时须升级当前版本。

验证：

- Manifest 全部 74 项测试通过，含合法 L1–L4、非法分级/路径、重复声明、注解与文件合并、record 去重、上报前拒绝非法清单，以及真实 HTTP 启动状态验证；依赖模块 4 项架构检查通过。命令：`cd backend && ./mvnw -o -pl forge-sdk/forge-sdk-manifest -am test -Dtest=ResourceManifestCollectorTest,ManifestReporterTest,ManifestReportingTest,OnboardingStatusHttpTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`。日志：`/tmp/field-masking-01-final-verification.log`。
- Sub-App 生成器现有 2 项测试通过：`cd frontend/packages/sub-app-kit && node --test bin/create.test.ts`。日志：`/tmp/field-masking-01-sub-app-template.log`。
- 受影响 SDK 模块 Spotless 格式检查与 `git diff --check` 通过。TDD red 日志分别位于 `/tmp/field-masking-01-red-{file,grade,path,annotation,report}.log`，对应实现后测试全部通过。
- 并行工作区曾覆盖本地 Maven SNAPSHOT 导致单模块读取旧 core；最终验证改用 `-am` reactor，使用本工单源码依赖，已消除制品漂移。完整平台端到端与实际脱敏执行留待依赖此契约的后续工单验收。
