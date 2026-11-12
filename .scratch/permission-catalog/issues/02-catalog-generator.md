# 02: 目录生成与产物入库

**What to build:** 按 [spec](../spec.md) 的“实现决策”，根脚本合并各服务 Manifest，生成确定性的 `permission-catalog.json` 并入库。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 脚本读取各服务 Manifest 产物，合并、去重、排序
- [ ] 产物包含 `schemaVersion`、权限码、服务、名称、分组、状态
- [ ] 输出 UTF-8、LF、末尾换行；不含时间、路径或运行期数据
- [ ] 产物提交到 `docs/contracts/permission-catalog.json`
- [ ] 一条命令可重新生成；生成失败时给出缺失服务或非法编码
- [ ] 测试覆盖：合并、排序、重复编码、缺失产物

## Comments
