# 02: 界面与 demo

**What to build:** 更新 demo-service 的导入接口和 `ImportDialog`、`IoTaskList`，让用户完成确认。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] demo-service 提供确认接口，并使用与提交时相同的校验和写入逻辑
- [ ] 界面显示将写入的行数和确认操作，确认地址由调用方传入
- [ ] 非提交人看不到确认操作，过期后明确提示
- [ ] 重新校验失败时可下载新的 Error Report
- [ ] Playwright 覆盖确认写入和重新校验失败

## Comments
