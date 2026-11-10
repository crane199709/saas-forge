# 失败响应追踪日志验收

日期：2026-11-10。

## 实现

- core 共用失败摘要格式，包含 method、route、status、code、traceId；4xx INFO 不带堆栈，5xx 保留调用方级别及异常。
- MVC 和容器错误响应在统一出口补齐日志；已有 5xx 日志携带相同元数据，并通过请求标记避免重复记录。
- Servlet 安全直接写出及 IAM 共用写出器补齐摘要。
- 网关本地错误统一记录，补齐 ForgeException 5xx；下游返回的错误继续原样透传，不增加网关摘要。
- 容器 /error 使用原请求路径及观测属性中的 traceId；网关临时绑定租户上下文后恢复外层 MDC。
- 摘要只读取元数据，清除查询部分、控制字符并限制长度；未改变审计逻辑、认证规则或成功响应行为。

## 回归信号

修复前执行新增 FailureLogTest：8 项中 7 项失败，均为预期 1 条摘要、实际 0 条。

修复后重跑最初临时复现（使用真实业务错误码 tenant.currentIpNotIncluded、公共异常处理器和请求观测过滤器）：

```text
PROBE status=400 traceInResponse=true totalLogEvents=1 matchingLogEvents=1
PROBE status=500 traceInResponse=true totalLogEvents=1 matchingLogEvents=1
```

HTTP 客户端会重试部分 503，每次重试是不同 traceId 的独立请求；网关去重断言按响应所属 traceId 检查。

## 完整验证

在 backend 执行：

```sh
./mvnw -o -pl forge-sdk/forge-sdk-core,forge-sdk/forge-sdk-web,forge-sdk/forge-sdk-security,forge-gateway verify
```

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| forge-sdk-core | 32 | 0 | 0 | 0 |
| forge-sdk-web | 47 | 0 | 0 | 0 |
| forge-sdk-security | 17 | 0 | 0 | 0 |
| forge-gateway | 55 | 0 | 0 | 0 |
| 合计 | 151 | 0 | 0 | 0 |

包含架构测试与 Spotless 格式检查。真实 Servlet 容器、Redis 测试容器与网关回环请求均已执行。覆盖 400/401/403/404/429/500/503、堆栈级别、摘要脱敏、上下文和去重；成功响应与下游错误透传不新增失败摘要。

## 生效范围

代码和本地 SDK 制品已更新。未重启现有开发进程；使用这些 SDK 的服务及网关重启后生效。未新增日志采集或留存配置，也无法恢复旧请求未生成的日志。未执行全仓服务测试，改动涉及的四个模块已完成完整 verify。
