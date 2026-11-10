# 01: 上传、预览与发送

**What to build:** 在成员页提供批量邀请：下载模板、上传 `.xlsx`、本地校验并预览、确认后以并发 3 逐行调用现有邀请接口，结束后显示计数并刷新列表。邀请接口的响应增加 `resent`，用于区分已邀请和已重发。规则见 [spec.md](../spec.md) 第 2、3、4、5 节。

**Blocked by:** None

**Status:** ready-for-agent

- [x] `POST /members/invitations` 响应增加 `resent`，由 `MemberService.invite` 邀请前后的未离开成员数判断；`MemberManagementTest` 覆盖新邀请、已离开成员重新邀请和重发
- [x] 调研并选定 Excel 解析库，按需加载，不进入首屏；选择理由写入本工单 Comments
- [x] 入口需要 `iam:member:manage`，模板含联系方式、角色、主部门三列
- [x] 非 `.xlsx`、超过 500 行、表头不符时整个文件拒收
- [x] 联系方式校验与 `SendCodeRequest.TARGET_PATTERN` 一致，文件内重复报错，邮箱不区分大小写
- [x] 角色按名称、主部门按名称路径匹配，匹配不到或匹配多条时该行报错，Owner 不可选
- [x] 缺少 `iam:role:manage` 或 `iam:org:manage` 时，填了对应列的行报错
- [x] 预览显示将邀请人数与错误明细，只发送有效行，没有有效行时不能确认
- [x] 各行结果按第 5 节归类，单行失败不影响其他行，不自动重试
- [x] Vitest 覆盖以上规则；Playwright 覆盖上传、预览、确认发送与结果

## Comments

### Excel 解析库选型（2026-11-10）

选用 `read-excel-file@9.3.12` 读取、`write-excel-file@4.1.1` 生成（同一作者，MIT），都只用 `/universal` 入口。

| 候选 | 结论 |
|---|---|
| `read-excel-file` + `write-excel-file` | 采用。持续维护（2026 年仍有发版），依赖只有 `fflate`、`saxen` 等小包；读写两包各自按需加载 |
| `xlsx`（SheetJS） | 不用。npm 上停在 0.18.5，带已公开的原型污染与 ReDoS 漏洞，修复版只从官方 CDN 的 tarball 发布，不走 npm 供应链 |
| `exceljs` | 不用。最后发版 4.4.0（2023），依赖 `archiver`、`unzipper`、`fast-csv` 等 Node 包，浏览器端体积大，需要的能力（单表读、表头写）用不到 |

- 用 `/universal` 而不是 `/browser`：`/browser` 内部起 Web Worker，jsdom 下无法运行；500 行的文件在主线程解析没有可感知的卡顿。
- 两个库只在 `bulk-invite.ts` 的函数里 `import()`，构建产物中是独立的 `universal-*.js` 块，首屏与成员页 chunk 都不含它们（已用 `vite build` 核对）。
- 版本取发布超过一个月的版本，不追最新的 9.3.14（发布不足两周）。

### 页面简报

- 使用者：租户管理员（`iam:member:manage`）。主任务是客户入驻时一次邀请大量成员。
- 位置：成员页工具栏右侧，「邀请成员」仍是唯一的实心主按钮，「批量邀请」用默认按钮放在它左边，属于低频操作。
- 载体：`el-dialog`（`dialogMd`），按选择文件 → 预览 → 发送中 → 结果四步切换内容，不另开页面。
- 一眼可见：预览的「将邀请 N 人 / M 行有错误」与错误明细表（行号、联系方式、原因）；结果的四个计数与失败明细。
- 填写说明放在模板的第二个工作表「填写说明」里，页面上不加说明文字；上传时只读第一个工作表。

### 实现说明

- 规则集中在 `member/bulk-invite.ts`：`parseInvitations`（表头、行数、联系方式、重复、角色与部门匹配、权限）与 `sendInvitations`（并发 3、按第 5 节归类、不重试）。对话框 `member/BulkInviteDialog.vue` 只管步骤与展示。
- 文件内重复时，第一次出现的行有效，之后的行报「与第 N 行重复」。
- 结果的「失败」计数包含预览时的错误行；失败明细把本地错误与接口失败按行号合并。
- 留给 02 的扩展点：`sendInvitations` 的 worker 循环取下一行前检查停止即可得到「未处理」；失败明细数据（`failures`）已按行号整理，可直接写成失败清单；表头校验目前拒收第 4 列及以后的非空表头，02 放行「失败原因」列即可。
- 视觉基线：新增 `bulk-invitation-{select,preview,result}-{desktop,laptop}.png`；`members-*.png` 重新生成，因为工具栏多了「批量邀请」按钮。
