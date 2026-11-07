# 01: SDK 状态流转与确认时重新校验

**What to build:** 按 spec 第 2、3 节，修改 `ExcelIo` 和 `IoTasks` 的导入流程。

**Blocked by:** storage-purpose/02

**Status:** ready-for-agent

- [ ] 校验通过进入待确认并记录行数，此时不写业务数据
- [ ] `confirmImport` 由条件更新保证只成功一次，只有提交人可确认
- [ ] 确认请求落到其他实例或重启后仍能完成
- [ ] 确认时重新校验，失败生成新的 Error Report 且一行不写
- [ ] 待确认任务不被判定为中断、不占队列，24 小时后过期并清理源文件
- [ ] 更新接入指南与 `CHANGELOG.md`

## Comments
