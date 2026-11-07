# 02: 迁移调用方

**What to build:** 把 `IoTasks`、`ExcelIo`、`OrderAttachmentService` 和脚手架迁移到 Storage Purpose。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] IO 使用 `io-source`、`io-result`，导入只接受 xlsx
- [ ] Order 附件声明自己的用途和限制
- [ ] 无用途的重载全部删除，迁移覆盖所有接入服务与脚手架
- [ ] 前端展示上传被拒的错误提示
- [ ] IO 与附件的现有测试通过，并补充被拒场景
- [ ] 更新接入指南和 `CHANGELOG.md`

## Comments
