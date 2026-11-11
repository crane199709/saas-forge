# CI 按变更范围执行

PR 和 `main` 推送保留四个质量工作流及原有检查名称。每个检查先检出代码、验证选择规则，再计算 Git 差异；无关的依赖安装、构建和测试步骤跳过，检查仍正常返回结果。这样无需调整分支保护，也不会因整个工作流被路径过滤而让必需检查一直 Pending。

## 执行范围

| 修改内容                                                  | 执行的重检查                                                   |
| --------------------------------------------------------- | -------------------------------------------------------------- |
| 普通 Markdown、`docs/`、`.scratch/`、视觉截图基线         | 无                                                             |
| `docs/standards/`（随脚手架交付的规范）                   | Backend quality、Frontend quality、Template smoke              |
| 普通前端 mock E2E、视觉测试代码、前端单测、平台运营端页面 | Frontend quality                                               |
| `backend/**/src/test/`、`src/it/`                         | Backend quality                                                |
| 租户端或业务 Sub-App 源码、前端共享包、锁文件及构建配置   | Frontend quality、Main flow E2E、Template smoke                |
| 后端源码、POM、Maven Wrapper                              | Backend quality、Main flow E2E、Template smoke                 |
| `frontend/e2e/main-flow/main-flow.spec.ts`                | Frontend quality、Main flow E2E                                |
| `frontend/e2e/main-flow/template-smoke.spec.ts`           | Frontend quality、Template smoke                               |
| 真实 E2E 共享配置和部署夹具                               | Main flow E2E、Template smoke；前端文件也执行 Frontend quality |
| `deploy/postgres/`、`deploy/nacos/`                       | Backend quality、Main flow E2E、Template smoke                 |
| 模板冒烟脚本、SDK 发布与消费脚本                          | Template smoke                                                 |
| 某个质量工作流自身                                        | 该工作流                                                       |
| 公共 CI Action、范围选择脚本、其他未知路径                | 全部                                                           |

多种改动取并集。模板冒烟也会启动当前 IAM、网关、平台服务和租户端，因此它们的运行代码变化需要执行模板冒烟。archetype 内嵌的 `src/test/` 属于生成工程内容，按后端源码处理；平台运营端 `package.json` 参与发布版本一致性校验，按前端共享配置处理。

普通 mock E2E 会导入 `frontend/e2e/visual/support.ts`，修改它仍执行前端检查。通知专项 E2E 当前没有自动运行的工作流，维持原有手动运行方式，其测试代码改动仍由前端类型检查覆盖。

## 比较范围与全量验证

- PR 使用 base 和 head 的共同祖先到 head 的差异，避免把目标分支新增的改动误算为本 PR。
- push 使用事件中的 before 到 after，覆盖一次推送中的全部提交。
- 禁用 Git 重命名检测，旧路径和新路径都参与选择；删除文件也参与选择。直接读取 Git 差异，不受 GitHub 路径过滤的 300 文件上限影响。
- 新分支、历史不可达或事件内容不可用时回退全量。未知文件路径也回退全量。
- 手动触发各质量工作流及发布标签始终全量。可复用调用的 `force-full` 输入默认 `true`，现有发布工作流无需额外传参。

四个工作流仍会出现在 Actions 中，无关改动只运行轻量步骤。每个检查的 Step Summary 会显示是否执行重检查及原因；检测或规则测试失败会让原检查失败。

例如，只修改 `frontend/e2e/import-confirmation.spec.ts`、`storage-purpose.spec.ts` 和 `tenant-ip-allowlist.spec.ts`，只执行 Frontend quality 的重检查。

## 修改规则与本地验证

规则集中在 [changes.py](../scripts/ci/changes.py)，由 [ci-scope Action](../.github/actions/ci-scope/action.yml) 复用。修改规则时同步更新回归场景；Action 每次运行都会执行这组轻量测试。

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v
actionlint
```

规则测试使用临时 Git 仓库，覆盖依赖选择、多提交 push、PR 分支分叉、重命名、删除、特殊文件名、超过 300 个文件、全量回退及 GitHub 输出。

GitHub 行为参考：[路径过滤与触发条件](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)、[必需检查与跳过行为](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)。
