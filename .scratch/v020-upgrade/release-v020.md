# v0.2.0 发布执行记录

Status: needs-info

2026-11-17：用户授权核对配置、按既有流程发布 SaaS Forge v0.2.0 并完成工单 09；保留已有未提交改动，不切换运行环境。Maven 与镜像已经发布，当前准备 npm 专用恢复；工单 09 尚未完成。

## 来源与发布配置

- 标签 `v0.2.0`：提交 `10b8f30d82fcb036b550bc55a8a2540fc7576f46`，annotated tag 对象 `0ae2a779d88074e04388591896b4875a641d7019`，未移动或重建标签。
- 开发主线随后进入 `0.3.0-SNAPSHOT`，版本提交 `b60923412803da8eaa7627c2fd91c505e9ee4389`。主线与标签已原子推送。
- 后端、前端应用/包、锁文件、workspace 和规范目录的实际打包输入与已验候选 `9f61c8a0 / 0.2.0` 一致。后续新增的两项 CI 修复不进入发布包。
- 已核对 GitHub Variables：Maven 当前仓库 GitHub Packages；npmjs.org；npm access 为 public；GHCR 前缀 `ghcr.io/crane199709/saas-forge`。Secret `NPM_RELEASE_TOKEN` 已存在，但存在不代表有效。

## 发布门禁修复与结果

初次远端主线验证发现两项实际 CI 阻碍，已最小修复并重新通过四门：

1. 普通 Playwright 配置误收集真实模板独立宿主用例。`e225a216` 排除该专用目录；实际收集由 73 项变为 72 项，原有 72 项 mock 用例全部保留，专用配置仍收集真实宿主 1 项。
2. Sub-App 升级样例改写依赖后，CI 默认 frozen 锁文件阻止升级。`45c051cb` 仅在升级安装步骤显式重写锁文件；`CI=true` 下实际 pnpm 红绿复现通过，随后 frozen 安装通过。正常工程的 frozen 门禁保持原样。

修复后的主线质量门全部通过。标签[发布流水线 44035010646](https://github.com/crane199709/saas-forge/actions/runs/44035010646)的版本检查、Backend、Frontend、Main flow、Template smoke（含四条升级路径）也全部通过。

## 制品状态与阻碍

| 阶段 | 结果 |
| --- | --- |
| 六个 GHCR 镜像 | `images / images` 作业成功；六个 0.2.0 镜像已推送，并在 CI 按 digest 回拉及核对 image ID。 |
| Maven 发布 | 第二次尝试的 Maven reactor 于 07:35:25 UTC BUILD SUCCESS，0.2.0 的 BOM、父 POM、SDK、architecture、archetype 及源码包已上传。 |
| npm 发布 | 第一次尝试于 07:10:02 UTC 在身份预检返回 E401，未开始上传。用户更新 Secret 后，第二次认证通过，但于 07:35:36 UTC 首次 npm 发布返回 EOTP，需要发布用的二次认证能力。 |
| npm 凭据恢复 | 用户已再次更新现有 Secret，确认处理 Bypass 2FA 发布令牌要求；使用 npm 专用恢复入口验证，不再执行 Maven deploy。 |
| 工单 09 | 保持 needs-info，尚未运行实际发布制品的只读消费，所有验收项不提前勾选。 |

六个镜像为 forge-gateway、forge-iam、forge-platform、demo-service、tenant-app、platform-console。实际 digest、source 与日志摘要封存在 `/private/tmp/v020-release-20261117/images-publication.json`。

## 恢复步骤

第一次更新凭据后的重试已完成 Maven 发布，因此不能再次重跑原始 publish 作业。新增仅手动触发的 `Recover v0.2.0 npm publication`：固定 checkout 原标签与 `10b8f30d`，核对原发布质量门全部通过和 Maven 前置已发布，沿用原标签的 build/pack/check 命令，只发布缺少的三个 npm 版本并等待可消费。既有版本跳过，Maven 与镜像不重复发布。恢复只使用现有 npm Secret，未修改账号或包的 2FA 策略。

当前 npm 自动化发布要求在创建 Granular Access Token 时选择发布权限并启用 Bypass two-factor authentication；如果包选择禁止 Token 发布，不能通过此方式恢复。用户负责配置自己的发布令牌，令牌不进入聊天或仓库；官方依据为 https://docs.npmjs.com/requiring-2fa-for-package-publishing-and-settings-modification/ 。

Maven/npm 实际发布后，在 `v0.2.0` 源码 ref 执行 Verify published SDK / `publish.sh consume v0.2.0`，并保存目标归档、SHA-256、与候选的内容差异及来源对应关系。通过后才关闭工单 09 和父规格。不覆盖同版本、不移动标签、不切换共享或生产环境。

执行材料保存在 `/private/tmp/v020-release-20261117/`：配置及目标预检、远端 refs、失败日志、成功镜像日志、CI 测试收集与 pnpm 锁文件红绿证据。GitHub 连接器出现传输失败，GitHub CLI 状态请求偶发 EOF；官方 API 的独立 HTTP 客户端可正常读取，查询失败没有触发重复发布。
