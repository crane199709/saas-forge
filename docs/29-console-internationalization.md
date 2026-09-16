# Console 国际化基线

状态：公开接口、资源门禁与验收矩阵已实现，**两个 Console 的 Locale 收敛未完成**。Q1–Q14 的公开接口、交付顺序及验收矩阵已按本文交付，PRD [Issue #117](https://github.com/crane199709/saas-forge/issues/117) 已关闭；资源门禁 `pnpm --dir consoles run validate:i18n` 已接入 `build:workspace` 与 `verify:workspace`。本文继续作为实施规格；包名与当前实现状态见下方说明与[当前仓库事实与衔接点](#当前仓库事实与衔接点)，已确认但未达成的项集中在[未达成项](#未达成项)。

> **包名更新**：本文原先以 `@saas-forge/react-shell` 与 `@saas-forge/design-system` 表述共享 UI 载体，二者已按 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替换为 `@saas-forge/admin`（Vue 3 + Element Plus + Soybean Admin）。正文已按当前仓库更新，语言解析、偏好存储、资源门禁与回退语义不变。

关联：[MVP 开发计划](16-mvp-development-plan.md)、[Console 设计规范](25-design-system.md)、[认证 Runtime](28-console-authentication-runtime.md)、[Locale 偏好边界 ADR](adr/0040-console-locale-is-a-local-ui-preference.md)。

PRD：[Issue #117 — 建立 Console 中英文国际化基线与可扩展 Locale 契约](https://github.com/crane199709/saas-forge/issues/117)，属于阶段交付总计划 #88。

## 已确认决策

### 1. 首版覆盖范围

- 支持 `zh-CN` 与 `en-US`，完成当前已交付的两个 Console 和共享组件的双语化。
- 覆盖登录、认证恢复、Tenant 选择与切换、导航、表单校验、空态、错误态及无障碍标签。
- 用户输入、Tenant 名称等业务数据保持原值；后续业务页面随对应阶段补齐翻译。
- 保留其他语言的构建期扩展接口，不将未来语言视为本次已支持语言。

### 2. 初始语言

- 有效的手动本地偏好优先于浏览器语言。
- 没有有效偏好时，按浏览器语言偏好顺序查找首个支持的语言；对每个候选先精确匹配，再按语言系列回退。当前将 `zh` 系列映射到 `zh-CN`，将 `en` 系列映射到 `en-US`。
- 全部不匹配时回退 `en-US`；当前繁体中文浏览器偏好也显示简体中文。
- 示例：`fr-FR → en-GB → zh-CN` 选择 `en-US`；只有 `zh-TW` 时选择 `zh-CN`。

### 3. 本地 UI 偏好

- 语言偏好按 Console Origin 保存，不归属于账号、Tenant 或 IAM 状态。
- 登出、换账号、切换 Tenant 均保留偏好；Platform Console 与 Tenant Console 各自独立，同 Origin 标签页同步。
- 存储不可用时仍允许本页切换；刷新后重新按浏览器语言选择。
- 首版只提供中文与英文选择，不提供“跟随浏览器”模式。
- 同步、冲突和异常处理见第 12 节；存储键的具体名称见第 13 节。

### 4. 切换行为

- 即时更新 Shell、已挂载 Remote 和公共组件的显示语言。
- 保留输入、路由、弹窗和进行中的请求；已经显示的本地提示随语言更新。
- 语言切换本身不触发业务请求，不通过刷新页面完成切换。

### 5. 新语言扩展

- 使用构建期语言注册表，集中声明语言标识、显示名称、匹配规则和资源。
- 新增语言需要完成注册、各资源翻译及组件语言适配，通过门禁后随制品发布，不要求逐个修改业务页面。
- 精确匹配优先于语言系列回退；例如未来启用 `zh-TW` 后，浏览器偏好 `zh-TW` 必须选中该语言，不能继续被映射到 `zh-CN`。
- 首版不建设运行时语言包下载服务。

### 6. Remote 与资源归属

- 各 Console 入口独占语言选择和偏好写入，Remote 只消费当前 Locale，不改变全局语言。
- 公共组件、共享 Shell 分别维护自己的公共文案；各 Console、Remote 维护各自业务文案。
- 资源使用独立命名空间，Remote 不得覆盖其他模块资源。
- 本次制定 Locale 传递契约，并用现有静态 Remote 消费夹具验证初始语言、即时更新和表单状态保留。
- 真实 Manifest/Remote 加载后的传递验收仍由对应后续阶段完成，夹具证据不代表真实加载链路已交付。

### 7. 翻译构建门禁

- 每个资源命名空间在所有已启用语言下，翻译键集合及插值参数集合必须一致；空文案阻断构建。
- 自动发现新增 Console、Remote 的资源，不依赖人工登记消费者名单。
- 不要求不同模块拥有相同翻译键。
- 门禁证明结构完整；翻译准确性仍需人工审阅和双语界面验收。
- 消息必须符合 ICU 语法；不同语言允许使用不同复数分支，不要求复数分类集合相同。

### 8. 格式化

- 提供统一日期、数字、金额格式化入口，按当前 Locale 展示。
- 时间点默认使用浏览器时区显示，金额币种由业务数据明确提供。
- 切换语言不改变时间点、币种、金额数值或 API 数据格式。
- 首版不增加时区设置界面。
- 继续遵循既有 [REST 数据表示契约](08-api-design.md)：时间点使用 UTC RFC 3339，日历日期使用不含时间和时区的 `YYYY-MM-DD`；格式化日历日期不得导致跨日偏移。精确小数和金额保留十进制字符串的精度，不得为显示而转换成有精度损失的数值。

### 9. 消息格式与依赖

- 消息采用 ICU MessageFormat；引入 `intl-messageformat`，构建门禁使用 `@formatjs/icu-messageformat-parser` 解析消息和提取参数。
- 首版只输出纯文本，不提供翻译资源中的 HTML 或富文本标签执行能力。
- 该选择复用现成的消息语法解析、转义、参数格式化和复数规则，避免自行实现及维护对应规则与回归测试；代价是增加依赖、制品体积和后续升级验证。
- 官方依据：[Intl MessageFormat](https://formatjs.github.io/docs/intl-messageformat/)、[ICU 语法](https://formatjs.github.io/docs/core-concepts/icu-syntax/)。具体兼容版本在实施时核验并锁入工作区 catalog 与 lockfile；此次设计未安装依赖。

### 10. 缺失翻译与安全恢复

- 构建时缺失翻译或格式错误直接失败。
- 生产环境缺少当前语言的消息时，回退到同一命名空间、同键的 `en-US` 消息。
- 英文也缺失或消息无法格式化时，由当前页面的错误边界显示安全恢复界面；不向用户暴露翻译键、原始异常或含义不明的操作按钮。
- 恢复界面本身必须具备不依赖故障消息查找的最小安全文案，避免递归失败；这不替代正常资源的完整性门禁。

### 11. 启动、故障和语言入口

- 语言初始化独立于认证，在首次应用渲染前确定 Locale 并设置文档 `lang`，此后切换时同步更新。
- 登录、配置失败、会话恢复页均提供语言选择；根级崩溃页使用最后已知语言，只提供恢复操作，不保证损坏后的语言选择器继续工作。
- 双语基础资源随应用打包，不依赖认证或网络请求才能显示启动、故障文案。

### 12. 偏好同步和浏览器语言变化

- 以本地存储最后成功写入的有效偏好为准；收到存储变化或标签页重新激活时重新读取当前存储，不使用迟到事件携带的旧值覆盖新偏好。
- 偏好删除或无效值均视为没有偏好，重新按浏览器语言选择。
- 读取失败保留本页语言；初始启动没有已知语言且读取失败时，使用浏览器语言匹配结果。
- 写入失败仍允许本页切换，但不承诺持久化或跨标签页同步。
- 没有手动偏好时响应浏览器语言变化；存在有效手动偏好时不以浏览器变化覆盖它。

## 当前仓库事实与衔接点

- `@saas-forge/i18n`（`consoles/shared/i18n`）提供 `SupportedLocale`、语言注册表、`resolveLocale`、`defineMessages`、`createTranslator` 与 `formatDate`/`formatInstant`/`formatNumber`/`formatMoney`；注册表位于 `src/locale-registry.json`，当前默认语言是 `en-US`。
- `@saas-forge/admin` 的 `runtime/context.ts` 提供 `useLocale()`：以 `resolveLocale(navigator.languages)` 初始化 Locale、读写 `sf:ui:locale`，监听 `storage`、`languagechange` 与 `visibilitychange`，并在变化时同步 `document.documentElement.lang`；`useShellText()` 通过 `createTranslator({ namespace, locale, messages })` 翻译公共文案，公共资源按模块拆在 `src/messages/authentication/` 与 `src/messages/recovery/`。该 hook 目前只有 tenant-console-shell 使用（由 `ConsoleApplication.vue` 调用）。
- 两个 Console 各自维护业务资源并传给共享 Runtime：`tenant-console-shell` 在 `main.ts` 中用 `createTranslator` 构造导航文案后交给 `mountConsole`；`platform-console` 在 `src/messages/` 声明 `platformMessages` 并在 `use-platform.ts` 中接入。
- platform-console 的 Locale 由模板自带的 `src/locales/`（`vue-i18n` + `intl-messageformat`）持有：`App.vue` 把这个 `locale` 经 `consoleContextKey` 提供给 `@saas-forge/admin`，因此**同一页面只有一个 Locale 状态，不存在两处并行引用**。tenant-console-shell 走另一条路：`mountConsole` 渲染的 `ConsoleApplication.vue` 调用 `useLocale()` 自建 Locale。两个实现读写同一个 `sf:ui:locale` 键，但行为并不等价，差异见下方[未达成项](#未达成项)。收敛方向见 [ADR 0051](adr/0051-consoles-use-complete-soybean-applications.md) 与 Issue #199。
- 资源门禁 `consoles/scripts/validate-i18n-resources.mjs` 自动发现任一层级 `src/messages`、`src/locales` 下含 JSON 的目录，检查缺失/多余键、重复键、非字符串值、空白消息、ICU 语法、参数类型一致性与未允许的富文本标签；`build:workspace` 与 `verify:workspace` 均已接入，`en-US` 作为键的基准集合。
- 静态 Remote 消费夹具（`business-remotes/admin-consumer-fixture`）由宿主传入只读 `locale` 属性；真实 Manifest/Remote 加载尚未实现，其语言验收仍属后续阶段。
- 认证规格已要求 Problem 映射输出稳定本地语义键和安全参数，二者共用本切片的中英文资源。
- 浏览器存储安全验收已放行 `sf:ui:locale` 并限定为已启用语言枚举，继续拒绝其他敏感状态持久化。
- 配置加载或验证失败页在认证 Runtime 创建前即可出现；语言初始化因此在应用入口完成（`platform-console/src/main.ts` 先 `setupI18n(app)` 再挂载），根故障路径仍可取得语言。

### 未达成项

以下各点本文正文已作要求，但当前实现尚未满足。它们既是 [#199](https://github.com/crane199709/saas-forge/issues/199) 需要承接的范围，也是阅读本文时不能把正文当作现状的地方：

- **§12 的"标签页重新激活时重新读取"只在 admin 侧成立**。`@saas-forge/admin` 的 `useLocale()` 监听了 `visibilitychange`（`shared/admin/src/runtime/context.ts`），而 platform-console 的 `src/locales/index.ts` 只监听 `storage` 与 `languagechange`，没有 `visibilitychange`；platform-console 激活标签页后不会重读存储中的最新偏好。
- **platform-console 的语言集合写死为两个值**。`platform-console/src/locales/index.ts` 用 `type Locale = 'zh-CN' | 'en-US'` 与同值的 `supported()`，不读 `@saas-forge/i18n` 的语言注册表。因此 §5「新语言扩展」在 platform-console 上无法只改注册表完成，必须同时改这个文件。
- **富文本处理存在分歧**。platform-console 的 `messageCompiler` 用 `IntlMessageFormat(message, locale, undefined, { ignoreTag: true })`，而 `@saas-forge/i18n` 的 `createTranslator` 走自己的 ICU 路径；同一份消息在两个 Console 上的标签处理语义可能不同。
- **`index.html` 硬编码文档语言**。两个应用的 `index.html` 都写 `lang="zh-CN"`。两个实现都用 `immediate` 的 watcher 在渲染前把 `document.documentElement.lang` 改成实际 Locale，因此影响限于脚本执行前的短暂窗口，以及任何不执行 JS 的消费者（`en-US` 用户会先拿到错误的语言标注）。

## 已确认的实现契约

以下公开接口、资源门禁、验收矩阵及交付顺序已由用户整体确认，作为后续实施约束。

### 13. 包和公开接口

| 所属 | 公开接口或输入 | 责任 |
| --- | --- | --- |
| `@saas-forge/i18n`，位于 `consoles/shared/i18n` | `SupportedLocale`、语言注册表、`resolveLocale`、`defineMessages` | 纯 TypeScript；统一语言匹配、启用列表与类型，不导入 Vue/React 组件库、认证 Runtime 或访问浏览器存储 |
| 同一共享包 | `createTranslator({ namespace, locale, messages })` 返回 `translator.translate` | 封装 ICU、命名空间、参数校验与英文回退；资源按所属模块显式传入，不提供全局可变资源注册服务 |
| 同一共享包 | `formatDate`、`formatInstant`、`formatNumber`、`formatMoney`，显式接收 Locale | 分别处理日历日期、时间点、数值、金额；时间点可显式传入已知时区，否则采用浏览器默认时区 |
| `@saas-forge/admin` | `useLocale()`（`runtime/context.ts`，当前为包内接口）与 `useShellText()`，返回当前 Locale、`setLocale` 与公共文案查找 | 在认证启动之前管理唯一页面语言状态、偏好读写、事件订阅及清理、文档 `lang`；用于两个 Console 的一致集成 |
| Console 应用壳 | 底层组件语言由 Element Plus 的 `ElConfigProvider` 承接，模板文案由 `platform-console/src/locales/` 的 `vue-i18n` 实例提供 | 只消费共享 Runtime 的 Locale；模板实例不得成为第二份偏好来源（见上节已知缺口） |
| Remote 根组件 | 只读 `locale: SupportedLocale` 属性 | 初次挂载和切换时由宿主传入；Remote 通过共享包翻译自己的资源，不获取语言写接口 |

- 公开包只有受控稳定入口；页面不直接依赖 ICU 库，不复制匹配、存储或回退逻辑。`app-runtime` 继续只提供错误语义和安全参数，不持有 Locale 或翻译后的文案。
- 浏览器持久键采用 `sf:ui:locale`，值只能是已启用 Locale 的精确标识，例如 `zh-CN`；不保存账号、Tenant、来源列表、时间戳或整份偏好对象，不写 Cookie、URL、sessionStorage 或认证跨页消息。
- 非规范存储值和未启用语言值不作为有效偏好；浏览器候选按语言标签规范处理，使用 `navigator.languages` 的顺序，缺失时采用 `navigator.language`，仍无匹配则 `en-US`。
- 写入只发生在用户显式选择时；自动检测结果不写回存储。读取其他标签页的结果不触发再次写入，避免同步循环。写入失败的本页选择保持到下一次明确选择、成功读到更新后的存储状态或刷新；暂时无法读取时不清空本页选择。
- 语言选项显示语言自称，首版为“简体中文”和“English”；选择器使用 Element Plus 现有受控控件组合，登录前后使用一致语义。
- Locale 改变不更换组件 `key`、认证 Runtime 或路由实例，不重新挂载 Remote；已显示提示保存语义键和参数，在渲染时翻译。焦点保持在当前控件，不把语言变化当成路由跳转。
- 基础资源随 Shell 打包，Remote 自有资源随 Remote 制品打包；切换已挂载 Remote 的语言不另行下载资源。新语言需同时提供公共组件语言适配；未来右向左语言须另行完成方向与布局验收，不能仅注册语言便宣称支持。
- 基础安全恢复文案按语言注册表覆盖已启用语言；国际化自身失败也不会影响已有恢复操作的含义。应用脚本完全未加载的网络故障不视为可由应用语言切换器处理。
- `createTranslator` 处理计数时遵循 ICU 的语言规则；精确金额和小数通过专用格式化入口处理，再作为文本参数插入，不能隐式转成浮点数交给 ICU。格式化只改变显示，不为本切片新增业务舍入策略；没有已有字段规则时不丢弃原值有效精度。
- 日历日期不转换为本地时间点；已确认的传输格式和币种保持不变。错误提示只翻译本地安全文案，服务端原始 `title`、`detail` 不作为翻译资源或显示后备。

### 14. 资源和门禁

- 各包在 `src/messages/<module>/` 或 `src/locales/` 下以 `<Locale>.json` 存放扁平的语义键到 ICU 消息映射，所属包名作为命名空间；公共包按可独立 tree-shake 的模块拆分资源。`en-US` 为键的基准集合，所有已启用语言必须具备完整资源。
- 开发者使用类型化键调用，不依赖按中文原文生成键或运行时拼接未知键；资源中的业务参数不参与生成键。
- 门禁自动发现 Console、官方 Remote 和共享文案包，资源缺失的消费者不能被静默跳过；检查缺失/多余键、重复键、非字符串值、空白消息、ICU 语法、参数名与使用类型一致性、未允许的富文本标签。
- 复数分支可以不同，参数的语义与类型应相同；需要的 `other` 分支仍必须存在。分支内部也要递归检查参数。
- 门禁以失败退出码阻止构建，不将运行时英文回退视为翻译完成。测试夹具故意引入坏资源，证明各类缺陷确实会被拒绝。
- `build:workspace` 与 `verify:workspace` 必须接入翻译校验，并保留现有工作区、`@saas-forge/admin` 消费及浏览器门禁；发布 CI 使用同一门禁。单包直接构建是局部开发证据，不能作为绕过工作区门禁的发布路径。
- 安全存储断言仅增加 `sf:ui:locale` 和已启用语言枚举，不以通配前缀放行其他存储值。Remote 消费边界校验拒绝使用宿主语言写接口或自行保存偏好。
- 新语言验收可在测试配置中临时启用第三语言，证明精确匹配、类型/注册一致性和资源缺失拒绝；生产启用列表仍只有 `zh-CN`、`en-US`。

### 15. 验收矩阵

| 接口或用户路径 | 必须成立的证据 |
| --- | --- |
| 语言解析公开接口 | 手动偏好优先；按候选顺序逐个精确匹配和回退；繁体偏好、区域英语、未知语言、空列表、非法偏好及第三语言扩展 |
| 消息与格式化公开接口 | 中英文插值/复数、英文回退、双缺失安全失败；时间点跨时区显示、日历日期不偏移、精确数值和明确币种无损 |
| 资源门禁命令 | 正确资源通过；新增模块遗漏资源、键/参数差异、空白、重复键、ICU 错误均真实阻断构建 |
| 公共组件 | 中英文默认文案、无障碍标签、表格数量、表单校验、空态、反馈与错误态一致；代表状态满足窄屏、键盘和焦点要求 |
| 两个 Console 启动与故障 | 浏览器初始语言、已存偏好和 `html.lang` 正确；配置失败、登录、会话恢复可切换；根故障仍显示可理解的恢复操作 |
| 用户显式切换 | 切换后刷新保留偏好；登出/换账号/Tenant 切换不重置；输入、弹窗、路由、已有提示与进行中的请求保持；切换本身不发业务请求 |
| 同 Origin 标签页 | 修改后同步；竞争写入最终收敛到存储值；删除、异常值、迟到事件、重新激活、读取/写入失败与浏览器语言变化符合规格 |
| 不同 Console Origin | Platform 改语言不改变 Tenant Console；两者认证槽位、Token 与安全存储限制继续成立 |
| 静态 Remote 消费夹具 | 首次语言与宿主一致；切换后文案及公共组件一起更新；表单输入和已显示反馈保持；未获得语言写入权 |
| 实际产品浏览器路径 | 复用受控 TLS/Origin 和真实服务环境；核心路径以回退语言 `en-US` 完整执行，`zh-CN` 覆盖代表性认证、Tenant 选择/切换及拒绝/恢复路径 |

- 行为与状态保持测试通过调用方使用的公开接口或实际页面执行，不以私有 reducer、组件内部状态或函数调用顺序替代验收。
- 浏览器检查使用 Chromium 日常测试与 Chrome 产品验收，优先覆盖本切片的语言、同步和格式化能力；可复用既有认证安全证据，发生影响时运行相关回归。
- 真实 Console 验收复用 Fresh Compose 认证环境和脚本，以专用测试环境证明核心路径；不删除用户已有环境的数据卷。记录页面、操作、相关 API 结果、刷新后结果及 console 错误情况，不收集敏感原始值。
- 表格项的行为要求长期有效。第 1～4 项已随 Issue #117 交付；第 5 项的逐项证据与真实 Remote 加载后的语言验收仍按阶段记录，真实 Remote 加载器尚未实现。

### 16. 交付顺序与完成条件

1. 共享语言注册、消息/格式化公开接口与资源门禁，并验证第三语言扩展缝。
2. `@saas-forge/admin` 的公共组件默认文案、组件语言与最小安全恢复文案。
3. 各 Console 入口的语言初始化、偏好同步及认证文案。
4. 两个 Console 与静态 Remote 夹具接入，落实状态保持和故障路径。
5. 双语界面审阅、工作区/构建门禁和受控 Origin 浏览器验收，保存逐项证据。

本次设计不新增 IAM/用户资料语言字段、后端本地化 API、跨 Origin 偏好服务、翻译管理后台、动态下载语言包、真实 Remote 加载器、时区设置或其他产品能力。

[ADR 0051](adr/0051-consoles-use-complete-soybean-applications.md) 与 Issue #199 计划把自建 i18n 收敛到模板机制；在该迁移落地前，`@saas-forge/i18n` 与 `@saas-forge/admin` 的 Locale 仍是两个 Console 与共享组件的现行实现。
