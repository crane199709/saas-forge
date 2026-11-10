# 02: 两端导航按布局显示

**What to build:** 按 [spec](../spec.md) 第 3、7 节，实现共用的合并函数，`AppLayout` 支持三层可折叠分组，两个 Shell App 的导航改为按布局显示。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `@crane199709/ui` 新增合并函数：无布局时为默认树；按布局放置可见节点；不可见引用跳过；新节点放入最近的已在布局中的祖先分组末尾，否则顶层末尾；空分组不显示
- [ ] `AppLayout` 分组改为可折叠子菜单，支持三层，默认展开，分组带 `data-testid="menu-group-<key>"`，当前页高亮不变
- [ ] tenant-app 用合并函数生成导航，删除 `menuSections`；首页、通知中心固定在顶部；清单多层分组按原层级显示；未注册的 Sub-App 页面按不可见处理
- [ ] platform-console 用合并函数生成导航，默认顺序为 `consolePages`
- [ ] 单测覆盖 spec 第 8 节合并函数与 `AppLayout` 各项；两端现有导航测试通过
- [ ] 视觉回归更新两端导航截图

## Comments
