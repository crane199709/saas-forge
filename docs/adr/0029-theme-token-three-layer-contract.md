# 主题 token 是三层契约，并承担微前端样式隔离边界

主题 token 是 Shell 与 Sub-App 之间唯一的样式契约：Shell 或独立开发宿主的 `applyTheme` 把变量写到文档根节点，Sub-App 只读同一组变量。Module Federation 不提供样式沙箱，CSS 隔离依靠约定，主题 token 就是这条约定的落点；Sub-App 不得写文档根节点样式，也不得重定义 `--forge-*`。

token 分三层：

- **primitive**：原始档位，含间距、圆角、字号、阴影、层叠与字体族，以及主色。只增数值档位，不承载角色语义。
- **semantic**：角色色，不另建 `--forge-color-*`，直接使用 Element Plus 的 `--el-color-*`、`--el-text-color-*` 及现有边框、背景与填充变量。
- **component**：组件私有变量，只在本组件的选择器内定义，如菜单元素的变量覆盖；不写到文档根节点。

保留全部旧名、只追加布局档位，不设删除期。Sub-App 独立构建、独立部署，仓库外已生成的工程无法被追踪：不知道谁在引用哪个 token，任何「一个版本后删除旧名」的承诺都无法兑现，只会在升级时让外部工程静默失效。宁可让 `themeTokens` 与 `layoutTokens` 里留着看似冗余的名字，也不做不可验证的清理。

语义色沿用 Element Plus，是为了避免双源。另建一套 `--forge-color-*` 语义色，就要同步维护 Element Plus 主色的派生色（`--el-color-primary-light-3/5/7/8/9` 与 `--el-color-primary-dark-2`），同一个角色色在两处各自计算，必然漂移，也与既有规范「语义色沿用 Element Plus」冲突。`applyTheme` 将主色、派生色和统一中性色写入对应的 Element Plus 变量，Sub-App 读角色变量即可。

分层是为了让改动落在唯一归属的层：加一档间距只动 primitive，调整角色色只动 Element Plus 语义变量映射，单个组件的外观只动它自己的 component 变量。组件按名字引用 token，新增档位不需要逐个组件改内联样式；primitive 的数值变化不会把角色语义带进组件，反过来组件也不能把私有取值提拔成全局档案。

平台默认配色允许统一演进：保留 token 名称与三层归属，在 CHANGELOG 说明变化，并完成主按钮状态可读性与各入口的视觉验收后调整默认色值。2026-11-16 的统一浅色主题将主色改为靛蓝 `#4F46E5`、中性色映射为 Slate；它有意更新默认配色，不改布局尺寸、间距、字号或圆角。语义色仍只沿用 Element Plus，成功、警告、危险保持原有含义。静态 favicon 随默认品牌主色同步。

仓库外 Sub-App 在 Shell 中继承宿主主题，独立开发时依赖配套 UI/kit 的默认主题。升级须更新依赖与规范副本；写死的色值不会自动迁移，不自动重新生成或发布外部工程。诊断页 alternate 探针继续验证主色继承，不改变统一中性色，也不成为产品切色功能。

## Considered Options

- 一套扁平的 `--forge-*` 变量不分层：短期少一层概念，但原始档位、角色色与单组件取值混在一起，新增档位时无法判断该放哪，最终每个组件各写一份近似值。
- 另建 `--forge-color-*` 语义色：与 Element Plus 的语义色和主色派生链形成双源，需要持续对齐两套变量。
- 删除旧名并设一个版本过渡期：外部工程无法追踪使用情况，过渡期结束后删除仍可能直接破坏线上 Sub-App，且无法验证影响面。

## Consequences

- 新档位一律追加，现有名称与布局档位取值不改；`themeTokens`、`layoutTokens` 允许保留看似冗余的旧名。
- 需要新的语义色时，扩展 Element Plus 的变量映射或在组件层做局部映射，不新增 `--forge-color-*`。
- 判断归属：原始数值进 primitive；角色色进 semantic（Element Plus）；只有单个组件使用的取值进 component。
- 未被组件引用的新增 token 不改变像素，视觉基线保持原样。
