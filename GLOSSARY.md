# SaaS Forge

一套多租户 SaaS 基础平台：平台侧负责租户的创建与生命周期，租户侧提供身份、认证鉴权与数据权限，并通过 SDK 让开发者快速搭建接入平台的业务服务。

## 主体与租户

**Tenant（租户）**:
购买并使用 SaaS 的客户单位，是业务数据隔离的边界。
_Avoid_: 组织、企业、空间、Workspace

**Identity（身份）**:
一个自然人在整个系统中唯一的全局身份，持有登录凭证与全局基础资料，不属于任何租户。
_Avoid_: 用户、User、账号

**Membership（成员关系）**:
某个 Identity 在某个 Tenant 中的成员身份，承载其在该租户内的状态、资料、角色与属性。
_Avoid_: 租户用户、员工、TenantUser

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
Plan 中规定 Tenant 可使用哪些 Permission Code 与 Sub-App 的部分。

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

**Resource Manifest（资源清单）**:
业务服务按版本向平台申报的 Permission Code、Data Resource 与菜单的集合。

## 系统构成

**Platform Core（平台核心）**:
由平台自身运行的网关、平台服务与身份服务的总称。

**Business Service（业务服务）**:
开发者基于 Forge SDK 搭建、接入平台的业务后端服务。
_Avoid_: 应用服务、子系统

**Forge SDK**:
供 Business Service 引入的一组后端能力包与供前端使用的前端包的总称。

**Shell App（主应用）**:
承载登录、租户上下文与菜单，并在运行时加载 Sub-App 的前端外壳；有平台运营端与租户业务端两个。
_Avoid_: 基座、门户

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
