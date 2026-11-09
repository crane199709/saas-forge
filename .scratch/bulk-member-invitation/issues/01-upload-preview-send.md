# 01: 上传、预览与发送

**What to build:** 在成员页提供批量邀请：下载模板、上传 `.xlsx`、本地校验并预览、确认后以并发 3 逐行调用现有邀请接口，结束后显示计数并刷新列表。邀请接口的响应增加 `resent`，用于区分已邀请和已重发。规则见 [spec.md](../spec.md) 第 2、3、4、5 节。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] `POST /members/invitations` 响应增加 `resent`，由 `MemberService.invite` 邀请前后的未离开成员数判断；`MemberManagementTest` 覆盖新邀请、已离开成员重新邀请和重发
- [ ] 调研并选定 Excel 解析库，按需加载，不进入首屏；选择理由写入本工单 Comments
- [ ] 入口需要 `iam:member:manage`，模板含联系方式、角色、主部门三列
- [ ] 非 `.xlsx`、超过 500 行、表头不符时整个文件拒收
- [ ] 联系方式校验与 `SendCodeRequest.TARGET_PATTERN` 一致，文件内重复报错，邮箱不区分大小写
- [ ] 角色按名称、主部门按名称路径匹配，匹配不到或匹配多条时该行报错，Owner 不可选
- [ ] 缺少 `iam:role:manage` 或 `iam:org:manage` 时，填了对应列的行报错
- [ ] 预览显示将邀请人数与错误明细，只发送有效行，没有有效行时不能确认
- [ ] 各行结果按第 5 节归类，单行失败不影响其他行，不自动重试
- [ ] Vitest 覆盖以上规则；Playwright 覆盖上传、预览、确认发送与结果

## Comments
