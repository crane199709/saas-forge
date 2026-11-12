# SaaS Forge

一套多租户 SaaS 基础平台：平台侧负责租户的创建与生命周期，租户侧提供身份、认证鉴权与数据权限，并通过 SDK 让开发者快速搭建接入平台的业务服务。

## 主体与租户

**Tenant（租户）**:
购买并使用 SaaS 的客户单位，是业务数据隔离的边界。
_Avoid_: 组织、企业、机构、空间、Workspace

**Identity（身份）**:
一个自然人在整个系统中唯一的全局身份，持有登录凭证与全局基础资料，不属于任何租户。
_Avoid_: 用户、User、账号

**Membership（成员关系）**:
某个 Identity 在某个 Tenant 中的成员身份，承载其在该租户内的状态、资料、角色与属性。
_Avoid_: 租户用户、员工、TenantUser

**Bulk Invitation（批量邀请）**:
一次提交多个联系方式、逐个邀请其加入当前 Tenant 的操作；每个联系方式各自成败，互不影响，不是 Import Task。
_Avoid_: 批量导入成员、批量新增用户、批量创建账号

**Active Tenant（当前租户）**:
一次登录会话当前所处的那个 Tenant；任一时刻一个会话只有一个当前租户。
_Avoid_: 默认租户、选中租户

**Tenant Switch（切换租户）**:
同一 Identity 将会话的 Active Tenant 换成其另一个 Membership 所在的 Tenant。

**Operator（平台运营人员）**:
在平台侧管理租户与平台配置的人员，其账号独立于 Identity 体系。
_Avoid_: 平台用户、超级用户、Admin

**Realm（域）**:
一套相互独立的账号体系与凭证签发边界；系统有 Platform Realm（Operator）与 Tenant Realm（Identity + Membership）两个。
_Avoid_: 认证域、空间

## 租户生命周期与商业化

**Tenant Lifecycle（租户生命周期）**:
Tenant 从「待开通」经「试用、正常、停用、过期、归档」到「删除」的状态流转。

**Provisioning（开通）**:
创建 Tenant 后，平台与各服务为其准备初始数据和初始管理员的过程。
_Avoid_: 初始化租户、注册租户

**Plan（套餐）**:
Tenant 订购的商业规格，由 Feature Pack 与 Quota 组成，是该租户可获得能力的上限。
_Avoid_: 版本、Edition、订阅

**Feature Pack（功能包）**:
Plan 中规定 Tenant 可使用哪些 Permission Code 与 Sub-App 的部分。Permission Code 经挂载它的 Menu 归属该 Menu 指向的 Sub-App，可同时归属多个；未挂载在 Menu 上的不归属任何 Sub-App。
_Avoid_: 租户授权菜单、菜单授权

**Quota（配额）**:
Plan 中规定 Tenant 可使用资源数量上限的部分，如成员数、存储容量。
_Avoid_: 限额、Limit

**Cooling-off Period（冷静期）**:
Tenant 进入删除状态后的可撤回期。期满后各服务把该 Tenant 的数据标记删除（逻辑删除），不做物理清除。

## 组织与权限

**Owner（所有者）**:
对 Tenant 负最终责任的唯一 Membership，可转让，不可删除。

**Tenant Admin（租户管理员）**:
拥有该 Tenant Plan 范围内全部权限、可管理 Membership 与角色的预置角色。

**Department（部门）**:
Tenant 内的层级组织单元；一个 Membership 有一个主部门，可兼任其他部门。
_Avoid_: 组织、机构、Org

**Position（岗位）**:
Membership 在 Tenant 内担任的职务，可作为权限判断依据。
_Avoid_: 职位、Job

**Extended Attribute（扩展属性）**:
Tenant 为 Membership 自定义的附加属性，可作为数据权限的主体属性。

**Role（角色）**:
一组 Permission Code 与 Data Policy 的命名集合，授予 Membership 或 Operator。

**Super Admin（超级管理员）**:
Platform Realm 中唯一的预置 Role，拥有全部平台 Permission Code，在全部平台数据上为「全部」，不可修改或删除；系统始终保留至少一位状态正常的持有者。
_Avoid_: 超级用户、root、平台管理员

**Permission Code（权限码）**:
标识一项功能权限（菜单、按钮或接口操作）的唯一编码，如 `order:approve`。
_Avoid_: 权限点、权限标识

**Data Resource（数据资源）**:
业务服务声明的一类受数据权限保护的业务数据，及其可用于策略判断的资源属性。

**Data Policy（数据策略）**:
挂在 Role 上、以主体属性、资源属性与环境属性的条件表达某类 Data Resource 可见可改范围的规则。
_Avoid_: 数据规则、行权限

**Data Scope（数据范围）**:
Data Policy 的常用预设，如「本人」「本部门」「本部门及下级」。

**Menu（菜单）**:
Business Service 在 Resource Manifest 中声明、由 Shell App 展示的导航节点，分为指向页面的叶子与只含子节点的分组；叶子的可见性由所挂的 Permission Code 决定，分组在有可见叶子时显示，本身都不是授权单位。
_Avoid_: 菜单权限、导航权限

**Navigation Layout（导航布局）**:
Operator 或 Tenant 管理员为一个 Shell App 设定的 Menu 与 Shell Page 的排列与分组；只决定位置，不决定可见性。
_Avoid_: 菜单配置、菜单权限

**Resource Manifest（资源清单）**:
业务服务按版本向平台申报的 Permission Code、Data Resource 与菜单的集合。

**Permission Catalog（权限目录）**:
汇集仓内服务与核心组件声明的 Permission Code 的确定性清单，供前端、外部 Sub-App 与 CI 消费和校验；运行时权威仍是 Resource Manifest 与 IAM 的权限记录，不是第二事实源。
_Avoid_: 权限清单、权限表

## 账号安全

**First Factor（第一因素）**:
登录时证明「是谁」的首个凭证，即密码或发往已登记手机号、邮箱的一次性验证码。
_Avoid_: 主认证、基础认证

**MFA（多因素认证）**:
第一因素通过后，再以账号持有者手中的验证器生成的动态码完成的第二步认证。
_Avoid_: 二次验证、两步验证、2FA

**Authenticator（验证器）**:
绑定在 Identity 或 Operator 上、用于 MFA 的动态码生成方；Identity 只有一个，在其所有 Tenant 通用。
_Avoid_: 令牌、MFA 设备

**Recovery Code（恢复码）**:
绑定 Authenticator 时一次性发给持有者的一组备用码，每个只能代替动态码完成一次 MFA。
_Avoid_: 备用码、救援码

**MFA Reset（MFA 重置）**:
持有者丢失 Authenticator 与 Recovery Code 后，由有权限的 Operator 人工清除其 Authenticator 的操作；不存在自助重置。
_Avoid_: 找回 MFA、MFA 申诉

**MFA Requirement（MFA 要求）**:
Tenant 对其 Membership 必须完成 MFA 的准入要求；Tenant 只提出要求，不拥有也不管理 Authenticator。
_Avoid_: 租户 MFA、MFA 开关

**Password Policy（密码策略）**:
每个 Realm 一套、由平台设定的密码可接受规则；Tenant 不能自定，因为密码是 Identity 的全局凭证。
_Avoid_: 密码规则、密码复杂度

**Login Lockout（登录锁定）**:
账号在一段时间内认证失败过多后被临时拒绝认证的状态，到期自动解除，不存在永久锁定。
_Avoid_: 账号冻结、封号、停用

**Tenant IP Allowlist（租户 IP 允许列表）**:
Tenant 限定其 Membership 可从哪些 IP 地址或网段进入该 Tenant 的规则；不限制全局登录，也不影响同一 Identity 进入其他 Tenant。
_Avoid_: 白名单、IP 策略

## 数据安全

**Data Grade（数据分级）**:
对敏感字段的分类，决定字段的默认脱敏规则与导出限制；不参与功能权限或数据权限判断。
_Avoid_: 密级、敏感级别

**Sensitive Field（敏感字段）**:
Business Service 在 Resource Manifest 中声明、需要在导出、日志或指定在线接口中脱敏的字段。
_Avoid_: 敏感列、隐私字段

**Masking Rule（脱敏规则）**:
把敏感值转换为不可还原展示值的规则；由平台预置，Tenant 可覆盖。
_Avoid_: 脱敏策略、掩码

**Field Masking（字段脱敏）**:
按 Masking Rule 替换敏感字段原值的处理，覆盖导出、日志与声明的在线接口；不是加密，原文不能由脱敏值还原。
_Avoid_: 数据脱敏、加密

## 通知与待办

**Notification（通知）**:
发给单个 Membership、告知一件已发生事实的记录，可标记已读。
_Avoid_: 消息、站内信

**Announcement（公告）**:
Tenant Admin 面向本 Tenant 或 Operator 面向全部或指定 Tenant 发布、可撤回的广播。
_Avoid_: 系统消息、通告

**Todo（待办）**:
指向某项待处理业务的索引，其创建与关闭由所属 Business Service 决定；不是工作流，实际处理在对应 Sub-App 中完成。
_Avoid_: 任务、工单、审批

## 参考数据

**Dictionary（字典）**:
Business Service 声明的一组可选值，每个值由编码与名称组成，用于界面选择与取值校验；只有一层，不成树。
_Avoid_: 枚举表、码表、数据字典

**Dictionary Item（字典项）**:
Dictionary 中的一个可选值；业务数据保存它的编码。分为服务声明的内置项与 Tenant 新增的自定义项。
_Avoid_: 选项、字典值

**Extensible Dictionary（可扩展字典）**:
允许 Tenant 新增自定义项、停用内置项、调整排序与显示名的 Dictionary；其余 Dictionary 对 Tenant 只读。
_Avoid_: 自定义字典、租户字典

**Dictionary Change History（字典变更历史）**:
Tenant 对 Dictionary 的覆盖设置按次留存的历史快照，用于查看变更与比较差异；不包含 Business Service 在 Resource Manifest 中的字典声明演进。
_Avoid_: 字典版本、字典日志

**Parameter（参数）**:
Business Service 声明的一个有类型、有默认值的设置项；声明为可修改时，Tenant 可在取值范围内覆盖默认值。
_Avoid_: 配置项、系统参数、Setting

**Numbering Rule（编号规则）**:
Business Service 声明的、为某类业务数据生成 Business Number 的格式与计数方式；Tenant 不能修改。
_Avoid_: 编码规则、序列号规则

**Business Number（业务编号）**:
按 Numbering Rule 为业务数据生成的、在 Tenant 内唯一且连续不跳号的可读编号，如 `SO-20261104-0007`；不是主键。
_Avoid_: 单号、流水号、业务 ID

**Business Time Zone（业务时区）**:
全平台统一、用于确定业务日期（如 Business Number 中的日期段与计数周期）的时区；Tenant 没有各自的时区。
_Avoid_: 系统时区、服务器时区

## 定时任务

**Global Task（全局任务）**:
Business Service 或 Platform Core 声明的、按时间触发且不绑定任何 Tenant 的任务；多实例部署时同一时刻只在一个实例上执行。
_Avoid_: 系统任务、平台任务

**Tenant Task（按租户任务）**:
按时间触发、每次触发时对处于试用或正常状态的每个 Tenant 各执行一遍的任务；每遍都在该 Tenant 的上下文中运行，彼此失败隔离。
_Avoid_: 租户定时任务、多租户任务

**Task Run（任务运行）**:
Global Task 或 Tenant Task 实际执行的一次记录，由定时触发或 Operator 手动发起；因未抢到锁而跳过的触发不算。
_Avoid_: 任务日志、执行记录

## 导入导出

**Import Task（导入任务）**:
Membership 提交一个 Excel 文件、由 Business Service 异步校验，并经提交人确认后写入业务数据的一次作业；全部行通过校验才可确认，否则一行不写。
_Avoid_: 批量导入、上传任务

**Export Task（导出任务）**:
Membership 按列表查询条件提交、由 Business Service 异步生成 Excel 文件的一次作业；导出范围与提交时其在列表中可见的数据一致。
_Avoid_: 下载任务、报表导出

**Import Template（导入模板）**:
按导入模型自动生成、供 Membership 填写的 Excel 文件，字典列带可选项下拉。
_Avoid_: 导入样例、模板文件

**Error Report（错误报告）**:
导入校验未通过时生成的、在原文件每行后附上错误原因的 Excel 文件。
_Avoid_: 失败明细、导入日志

**Import Rollback（导入回滚）**:
把一次覆盖导入中被覆盖的业务数据恢复为导入前取值的操作；不删除该次导入新增的数据，也不等同于 Restore。
_Avoid_: 撤销导入、导入还原、回滚

## 系统构成

**Platform Core（平台核心）**:
由平台自身运行的网关、平台服务与身份服务的总称。

**Business Service（业务服务）**:
开发者基于 Forge SDK 搭建、接入平台的业务后端服务。
_Avoid_: 应用服务、子系统

**Service Credential（服务凭证）**:
一个 Business Service 用来向平台证明自身服务身份的随机凭证；有状态、可轮换、可停用，与 Resource Manifest、运维令牌相互独立。
_Avoid_: 上报令牌、Manifest Token、API Key

**Service Prefix（服务前缀）**:
平台为每个 Business Service 分配、出现在网关外部路径里的单个路径段，决定路由、Realm 与 OpenAPI 分组。
_Avoid_: 服务名、应用编码

**API Entry（统一接口入口）**:
浏览器侧访问 Business Service 的统一路径前缀，由 Shell App 去除后再转发网关；它不随服务数量增加而变化。
_Avoid_: 网关前缀、baseURL

**Forge SDK**:
供 Business Service 引入的一组后端能力包与供前端使用的前端包的总称。

**Shell App（主应用）**:
承载登录、租户上下文与菜单，并在运行时加载 Sub-App 的前端外壳；有平台运营端与租户业务端两个。
_Avoid_: 基座、门户

**Shell Page（内置页）**:
由 Shell App 自身提供、不来自 Resource Manifest 的页面，如租户端的成员管理与平台端的全部管理页。

**Workbench（工作台）**:
Shell App 首页的只读聚合页，汇总当前 Membership 的待办、未读通知与公告；不是 Sub-App，也不是可配置的仪表盘。
_Avoid_: 首页、仪表盘、Dashboard

**Sub-App（子应用）**:
独立构建、独立部署、在运行时被 Shell App 加载的业务前端。
_Avoid_: 微应用、插件

**App Registry（应用注册表）**:
平台中登记各 Sub-App 编码、各环境入口地址与版本的目录。

**Response Envelope（响应信封）**:
Business Service 与 Platform Core 对外接口统一的响应外层结构，成功与失败都使用，承载结果码、业务数据、错误说明与追踪标识。
_Avoid_: 统一返回体、Result、R、包装类

**Error Code（错误码）**:
标识一类失败原因的点分字符串，形如「领域.原因」，同时是面向用户文案的查找键。
_Avoid_: 业务码、错误编号、状态码

**Audit Event（审计事件）**:
记录登录、权限变更或业务操作「谁在哪个租户对什么做了什么、结果如何」的事实记录。
_Avoid_: 操作日志、日志

**Outbox（发件箱）**:
服务库中与业务数据同一本地事务写入的待发事件表，由 Forge SDK 在事务提交后投递到消息队列，保证「业务数据落库则事件终将送达」。
_Avoid_: 消息表、本地消息表

**Dead Event（死信事件）**:
已送达消费方、但处理重试耗尽后留待 Operator 处置的事件；发送端的 Outbox 事件不会成为 Dead Event。
_Avoid_: 失败消息、毒消息

**Storage Purpose（存储用途）**:
Business Service 声明的一类文件用途，规定该类文件可接受的类型与大小以及下载链接时长；Tenant 与 Operator 不能修改。
_Avoid_: 文件类型、文件分类、业务类型

**File Retention（文件保留期）**:
Storage Purpose 规定的文件可保留时长；到期后文件记录先标记失效，宽限期结束后再删除存储对象。
_Avoid_: 文件过期时间、TTL

**Restore（恢复）**:
使已被逻辑删除的资源重新对常规查询可见的操作；不存在集中回收站，也不提供彻底删除。
_Avoid_: 还原、回收站、撤销删除
