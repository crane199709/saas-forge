# 01: Manifest 敏感字段声明

**What to build:** 按 [spec](../spec.md) 的“实现决策”，Resource Manifest 新增敏感字段声明，SDK 支持注解扫描生成声明，并在启动与上报时校验。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] Manifest 支持敏感字段：字段路径、数据分级、可选默认规则；与现有 permissions、dictionaries、dataResources 同一契约
- [ ] SDK 提供敏感字段注解，构建时扫描并合并进 Manifest，重复声明或非法字段路径直接失败
- [ ] 校验分级只能是 L1–L4；字段路径格式与 Manifest 现有标识符规则一致
- [ ] 清单变更需要升 `forge.manifest.version`，接入指南与模板同步说明
- [ ] 测试覆盖：合法声明、重复声明、非法分级、非法字段路径

## Comments
