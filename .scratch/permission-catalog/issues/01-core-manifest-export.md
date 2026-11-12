# 01: 核心服务 Manifest 导出

**What to build:** 按 [spec](../spec.md) 的“实现决策”，让核心 IAM/Platform 也能输出与业务服务同格式的 Manifest JSON。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] IAM 与 Platform 的权限清单可通过构建/测试命令导出为 JSON
- [ ] 导出内容包含服务名、版本、权限码、名称、分组与 Realm
- [ ] 输出稳定：字段顺序与集合顺序确定，可直接参与字节比较
- [ ] 不依赖运行中的数据库
- [ ] 测试覆盖：导出成功、内容包含核心权限、重复导出字节一致

## Comments
