# OpenAPI contracts

REST 契约采用 OpenAPI 3.1，并在接口实现前评审。

[`common.yaml`](common.yaml) 是不含资源路径的公共组件文档，定义成功/失败响应、Problem Details、字段校验、游标分页、异步 Job 与 `Location` 响应头的可复用 Schema、Response 和正反例。资源契约必须通过 `$ref` 或 `allOf` 复用其中组件；例如集合响应以 `allOf` 组合 `Page` 并收窄 `items` 的元素类型，具体 Job 以 `allOf` 组合 `Job` 并定义自身成功结果字段。

[`v1.yaml`](v1.yaml) 是当前资源路径的唯一 OpenAPI 3.1 根契约。它先覆盖实施阶段 2、3 所需的认证、JWKS、Platform Tenant 管理及管理员初始化所需的最小权益前置链路；Runtime Permission、Feature、Quota 操作和其余资源在对应阶段评审后，以向后兼容方式追加到同一 v1 根契约。

每个 operation 必须声明唯一的 `x-saas.forge-service`，取值为拥有该公开接口的领域服务制品 ID。`tags` 仍只用于文档和客户端分组；构建根据经校验的 tag 与服务归属生成各服务的 Spring MVC 接口和 本模块 `target/generated-typescript-client` 下的 TypeScript Client。只有经评审并声明 `x-saas.forge-java-sdk: true` 的 operation 才进入 `sdk-core` Java REST Client，缺少标记即默认不发布；浏览器会话及依赖 Cookie、`Origin` 或 Fetch Metadata 的 operation 不得获得该标记。`sdk-core` 只在 `target` 中派生包含获批 operation 及其必要组件的临时生成视图，不建立第二份可编辑契约。全部生成物均不提交，手写 Controller 只能实现生成接口，不能自行声明 HTTP 路由。该发布决策见 [ADR 0041](../../docs/adr/0041-java-sdk-client-is-generated-from-an-explicit-safe-subset.md)。

`v1.yaml` 与其引用的 `common.yaml` 同时受已发布契约基线保护；删除路径、operation、输入/输出字段或收紧结构约束将导致 `./mvnw verify` 失败。
