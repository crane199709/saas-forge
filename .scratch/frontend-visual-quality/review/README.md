# 评审材料：5 张关键页面的前后对比

对应规格第 6 节决策 2（「5 张关键页面前后对比为主 + lint/基线为门槛」）与第 3 节故事 20。
材料本身不参与 CI，只为「变好看」提供可核对的依据。

- **「前」** = 整改前的 `main`：`git show main:frontend/e2e/visual/__screenshots__/<名字>.png`
- **「后」** = 集成分支 `frontend-visual-quality`（已含工单 01–05）的当前同名基线文件
- 合成图左「前」右「后」，两张都是 1440×900 原图，未缩放、未调色；生成脚本见 [make-compare.py](make-compare.py)，用 macOS + Pillow。
- 基线只在 macOS 生成；这 5 张在改动后连跑两次 `pnpm test:visual` 均通过（215 passed）。

| 页面 | 改了什么 | 是否达成决策 1 |
| --- | --- | --- |
| [login-tenant-desktop](login-tenant-desktop-compare.png) | **逐像素无变化**（0 px）：登录页不经过 `AppLayout`，没有菜单也没有空态，本次 L1/L2 的改动都没落到它上面 | 既未提升也未破坏。它保持整改前的克制观感，说明「补视觉层」没有变成给每页加装饰 |
| [workbench-empty-desktop](workbench-empty-desktop-compare.png) | 侧栏补图标；三张卡的空态从一行居中灰字，改为「20px 浅底圆形图标 + 标题 + 一个主操作入口」（变 22975 px，1.77%） | 达成。空态有了层次和下一步，图标是语义图标而不是品牌装饰；正文仍是 14px、卡片仍是同一档阴影 |
| [org-members-desktop](org-members-desktop-compare.png) | **只有侧栏变了**：菜单项各补一个 20px 图标，分组标题与叶子项仍靠缩进与层级区分；内容区逐像素相同（变 9077 px，全在侧栏） | 达成。扫读侧栏不再逐行读文字；表格没有为了「精致」加分隔线、斑马纹或更重的表头 |
| [roles-members-desktop](roles-members-desktop-compare.png) | 与上一张同因：只有侧栏图标变化，角色成员表逐像素相同（变 9082 px，全在侧栏）；40px 行高本来已是 Element Plus 默认值 | 达成。密度靠统一行高而不是压缩字号，长文本列与操作列没有折行 |
| [platform-tenants-desktop](platform-tenants-desktop-compare.png) | 侧栏图标补齐，运营端与租户端同一套菜单观感；内容区只有亚像素级抗锯齿差异（最大通道差 3，人眼不可见） | 达成。运营端没有引入自己的一套视觉语言；「专业克制」体现在两端菜单同构，而不是给平台端换配色 |

## 读这 5 张时要注意的事实

- 5 张里有 4 张的内容区几乎没变：L1 的可见改动集中在**侧栏图标**与**空状态**，列表页与登录页本身没有被重写（规格第 7 节明确 L3 不在范围内）。`workbench-empty` 是唯一展示空状态新旧对比的一张。
- 因此这组材料的价值是**证伪**：如果整改真的「换了一套观感」，列表与登录页会一起变；事实是没有。
- 密度（工单 04）刻意没有改变这 5 张中的任何表格像素：Element Plus 默认行高本来就是 40px，工单 04 只钉住默认值并修掉离群行。

## 基线有没有变

本次（工单 06 的评审修复）**没有基线提交**：空状态统一（`ListPage` 不再退回 `el-table` 自带空态）没有出现在任何视觉用例里——215 张视觉用例连跑两次全部通过，工作区基线文件与集成分支逐张一致。规格要求的「基线变化要逐张判断并单独提交」因此不触发。

## 已知限制：favicon 是静态 SVG

三个应用的 `favicon.svg` 把主色写死在文件里（当前都取 `themeTokens.primary`，`#409eff`），**不会跟随 `applyTheme`**：换主色时页面会变、标签页图标不会，必须人工同步这三个文件。

spec 第 5 节也写明 favicon 没有任何自动化接缝——Playwright 截的是页面内容，不含浏览器标签页。工单 02 的对应勾选保持未完成，需要人打开三个应用的标签页看一眼。

## 自查方式

```bash
# 取「前」，与工作区的「后」并排看
git show main:frontend/e2e/visual/__screenshots__/roles-members-desktop.png > /tmp/before.png
open /tmp/before.png frontend/e2e/visual/__screenshots__/roles-members-desktop.png

# 或者一次重建 5 张合成图（需要 Pillow）
python3 .scratch/frontend-visual-quality/review/make-compare.py
```
