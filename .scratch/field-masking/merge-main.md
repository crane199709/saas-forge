# 字段脱敏合并 main 验收

Status: resolved

日期：2026-11-13。合并前 `main` 为 `f764f47`，功能分支 `codex/field-masking` 为 `5bcde39`，共同基线为 `301237d`。采用普通双父合并，保留 main 的权限目录、SDK 依赖门禁与字段脱敏的全部提交。

## 冲突与集成修正

- `CHANGELOG.md`、`docs/standards/sub-app.md`、`frontend/packages/sub-app-kit/bin/create.ts` 三处文本冲突均保留双方内容；其余文件自动合并。
- Security 新增可选 `ch.qos.logback:logback-classic` 已加入本模块直接依赖白名单，并同步依赖门禁文档；未关闭中间件或 SDK 白名单检查。
- 原文查看使用的 `viewRaw` 动作按独立授权、审计用途登记到动作策略，不使用历史例外绕过检查。权限目录从当前源码真实导出后生成，保留原 28 个权限，新增 7 个脱敏权限，共 35 项。
- IAM、IAM Platform、Demo 的清单导出测试同步实际版本 8、6、8 及新增权限；保留精确权限集合、排序和字节确定性断言。

## 验证

| 项目 | 命令及结果 |
|---|---|
| 后端全部模块与生成工程 | `cd backend && ./mvnw -o install -Parchetype-it`；1,129 项测试，0 失败、0 错误、0 跳过；所有模块成功，生成工程独立构建成功 |
| SDK 依赖门禁 | `python3 backend/src/it/sdk-dependency-gate/check.py --offline`；15 项正负例全部通过 |
| 权限目录脚本 | `python3 -m unittest discover -s scripts/permissions -p 'test_*.py' -v`；19 项通过 |
| 真实 Manifest 导出 | IAM 2、Platform 2、Demo 1 项通过，完整后端构建再次执行并通过 |
| 目录历史与字节检查 | 当前构建导出后运行 `catalog.py --skip-export --check --previous <合并前 main 目录>`；35 项，与原 28 项历史兼容、无漂移 |
| 前端两端组件 | tenant-app 235、platform-console 100，共 335 项通过 |
| 前端及模板 | 两端 typecheck、tools typecheck、Prettier、stylelint 通过；sub-app-kit 生成测试 2 项通过 |
| 开发版本 | `python3 scripts/release/version.py dev` 输出 `0.2.0-SNAPSHOT` |
| Git | 无未解决冲突，`git diff --check` 通过 |

目录生成使用本次 `clean install -DskipTests` 安装的 SDK 与真实 `*ManifestExportTest` 产物。后端完整安装及导出完成后，再基于新鲜产物逐字节校验。新增权限为 `iam:data-security:{read,manage}`、`platform:data-security:{read,manage}`、`masking:{read,export,viewRaw}`。

验证日志：

- `/private/tmp/field-masking-main-backend-install.log`
- `/private/tmp/field-masking-main-dependency-gates.log`
- `/private/tmp/field-masking-main-permission-tests.log`
- `/private/tmp/field-masking-main-export-tests.log`
- `/private/tmp/field-masking-main-frontend-tests.log`
- `/private/tmp/field-masking-main-frontend-typecheck.log`
- `/private/tmp/field-masking-main-tools-typecheck.log`
- `/private/tmp/field-masking-main-sub-app-generator.log`
- `/private/tmp/field-masking-main-frontend-format.log`
- `/private/tmp/field-masking-main-frontend-style.log`

本次只验证并提交本地 main 合并结果，没有 push 或部署。页面源码与视觉基线相对功能分支未改变；原有 [功能验收](acceptance.md) 和 [双轴审查](review.md) 的适用边界保持不变。
