# v0.2.0 发布执行记录

Status: resolved

2026-11-17：用户授权核对配置、按既有流程发布 SaaS Forge v0.2.0 并完成工单 09；Maven、npm 三包与六个 GHCR 镜像均已发布，独立只读消费复验通过，工单 09 和父规格已 resolved。已有未提交改动保留，没有切换运行环境。`0.2.0` 与 `0.1.0` 均不是产品正式版，此处指制品发布。

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
| npm 首两次尝试 | 第一次于 07:10:02 UTC 在身份预检返回 E401，未开始上传。用户更新 Secret 后，第二次认证通过，但于 07:35:36 UTC 首次 npm 发布返回 EOTP。原始发布工作流保留 failure 结果。 |
| npm 专用恢复 | 用户再次更新现有 Secret 后，[恢复工作流 44043705141](https://github.com/crane199709/saas-forge/actions/runs/44043705141)成功发布三个缺失的 `0.2.0` 包，08:12:44 UTC 全部安装元数据及 tarball 可读。没有再次执行 Maven deploy。 |
| 工单 09 | [Verify published SDK 44044837346](https://github.com/crane199709/saas-forge/actions/runs/44044837346)在标签源码执行成功，08:14:06 UTC 完成 BOM/SDK 解析、npm 三包安装、类型、Vite、lint CLI 验证；09 已 resolved。 |

六个镜像的完整名称、digest、source 与日志摘要已持久保存于 [published-v020-provenance.json](published-v020-provenance.json)，同时封存在 `/private/tmp/v020-release-20261117/images-publication.json`。

| `ghcr.io/crane199709/saas-forge/` 下的镜像 | `0.2.0` digest |
| --- | --- |
| forge-gateway | `sha256:e33c7ac4829526d3f27409f949e4743662a945af471c4195aad6edce943b6dd1` |
| forge-iam | `sha256:884f3f885179264586c292dbb375459b3b1ba472cc75b515ebd09c6e6f3bde5d` |
| forge-platform | `sha256:100f4e47542a64fcd362a43fd420a6fcdb3e97d1d38a61a8ea8009914e2f1422` |
| demo-service | `sha256:814006d559a14319f2c1eeda7f504c85576e7e068df1331c40d1d83ee230b3d6` |
| tenant-app | `sha256:0490461a332296b1fab5e1fcdf3b18e265854cc3a7f8ce6a87e37c021ff00f1b` |
| platform-console | `sha256:6145b8f8ad527094c1760a350195458dd2209ade94396dd495b48522242c6594` |

## 恢复步骤

第一次更新凭据后的重试已完成 Maven 发布，因此不能再次重跑原始 publish 作业。新增仅手动触发的 `Recover v0.2.0 npm publication`：固定 checkout 原标签与 `10b8f30d`，核对原发布质量门全部通过和 Maven 前置已发布，沿用原标签的 build/pack/check 命令，只发布缺少的三个 npm 版本并等待可消费。既有版本跳过，Maven 与镜像不重复发布。恢复只使用现有 npm Secret，未修改账号或包的 2FA 策略。

本次恢复使用的 Granular Access Token 由用户在自己的终端配置到既有 Secret，按发布权限与 Bypass two-factor authentication 要求处理；未由 Agent 放宽账号或包的安全策略。相关规则见 [npm 官方文档](https://docs.npmjs.com/requiring-2fa-for-package-publishing-and-settings-modification/)。令牌没有进入聊天或仓库。

Maven/npm 实际发布后，已在 `v0.2.0` 源码 ref 执行独立 `Verify published SDK / publish.sh consume v0.2.0`。只读下载 22 个 POM、38 个 JAR、3 个 npm 包并记录 SHA-256；22 个 POM、3 个 npm 包逐字节等同候选，19 个源码 JAR 的文件内容一致，归档和 23 个 class 字节差异及其公开 API 核对见 [09 最终验收](acceptance-09.md)。发布流程与只读复验分别成功后关闭工单和父规格，未覆盖既有版本、移动标签或切换环境。

开发主线维持 `0.3.0-SNAPSHOT`。此次沿用既有 tag 触发的制品/镜像流程，没有新增 GitHub Release 页面或部署流程。Lowcode 原有三份未提交文件的内容与权限模式均与执行前记录一致；AI 仓库期间出现其他工作带来的提交和待提交文件，本次发布没有写入该仓库，也没有推送两个业务仓库。

执行材料保存在 `/private/tmp/v020-release-20261117/`：配置及目标预检、远端 refs、失败日志、成功镜像日志、CI 测试收集与 pnpm 锁文件红绿证据。GitHub 连接器出现传输失败，GitHub CLI 状态请求偶发 EOF；官方 API 的独立 HTTP 客户端可正常读取，查询失败没有触发重复发布。
