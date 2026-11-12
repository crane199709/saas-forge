# 统一服务凭证配置验收

日期：2026-11-12。用户确认“统一配置方式”后授权执行；本次为已完成接入规格的配置收敛后续工作。

## 实现

- IAM、Platform、Demo 的自身运维凭证统一读取 `FORGE_OPS_TOKEN`；Platform、Demo 的清单及 IAM 内部读取统一使用 `FORGE_MANIFEST_TOKEN`，移除静态默认值。
- Platform 不再内置服务令牌列表，完整 `forge.platform.ops.tokens` 来自专属 `forge-platform.yaml`；本地私有文件与受控 Nacos 使用同一结构。
- 增加各服务独立环境文件及完整映射样例；同步中英文 README、服务指南、规范、archetype、sub-app-kit 与 E2E 启动脚本。
- IAM 已弃用的静态引导 Map 保留兼容，不作为新服务的推荐接入方式。
- 代码提交：`c26fb41`，分支 `codex/unified-service-config`；已快进同步实际源码目录的 `codex/service-onboarding`，未推送、未合并 main。

## 自动验证

| 检查 | 结果 |
| --- | --- |
| IAM / Platform / Demo 模块全量 | 314 / 61 / 105 项，共 480 项通过，无失败、错误、跳过 |
| sub-app-kit 构建与测试 | 构建成功，7 项通过；初次沙箱拒绝监听，授权环境重跑通过 |
| 隔离真实服务接入 E2E | 3 项通过：凭证登记与立即停用、真实 Shell/远程业务 JSON、运行状态和真实网关身份 |
| 受影响后端及 archetype | clean install 成功；模板携带的业务服务规范与源文档相同 |
| 工具及脚本 | tools typecheck、kit 规范同步、`bash -n`、`git diff --check` 通过 |

日志：`/private/tmp/unified-config-backend-test.log`、`/private/tmp/unified-config-kit-test.log`、`/private/tmp/unified-config-e2e.log`。真实 E2E 的隔离容器随脚本退出清理。

## 本机迁移与实际 HTTP

实际目录：`/Users/liuhuan/workspace/coding/saas-forge`。

- 将原共享文件拆成 `.env.iam.local`、`.env.platform.local`、`.env.demo.local`；完整映射写入 `deploy/data/forge-platform.yaml`，含 IAM、Platform、Demo、Lowcode。四份文件权限均为 `600`，均不入库。
- IDE 三个运行配置分别加载对应文件，IAM 保留 `demo` profile。原共享文件声明的 IAM 静态引导兼容值照原值保留；各服务 Ops Token、清单凭证和 IAM 签名密钥未轮换。
- Lowcode 自身 `.env.local` 已符合统一变量约定，未修改。启动时发现其既有 JAR 未包含状态 SDK，使用当前本地 SDK clean package 后启动；保留原有源码未提交改动。
- 检查时上述后端及网关均未运行。本次用各自环境文件以独立 JAR 进程启动 IAM、Platform、Demo、Lowcode 与网关；当前进程不由 IDE 托管。启动日志与 PID 位于私有 `/private/tmp/forge-unified-config-runtime/`。
- 五个进程健康检查均返回 200；四个服务的正确 Ops Token 返回 200、错误 Token 返回 401。新 IAM/Platform/Demo 进程均未加载旧服务名前缀 Ops 变量。
- Platform / Demo / Lowcode 上报状态分别为 `REPORTED`，版本为 3 / 7 / 2。
- 网关四个 OpenAPI 路由均返回 200 且 `x-forge-service` 与目标一致；四条内部路径经网关均返回 401。
- 运行验证后移除旧 `.env.ops.local` 与 `deploy/data/platform-local.properties`；删除前逐字比较备份，确认期间没有新增修改。私有备份保留于 `/private/tmp/forge-unified-config-backup-890b947cfd55/`。

本次未向 Nacos 发布配置，未修改用户角色或登记新凭证。IAM 自身不使用 Manifest SDK 的状态端点；诊断中“未提供状态端点”的既有语义不变。配置与启动验证不代表所有业务授权或所有副本均完成验收。
