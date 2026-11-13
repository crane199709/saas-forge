# 字段脱敏双轴审查与修复

Status: resolved

审查范围：`git diff 301237d...fa489c4`。Standards 与 Spec 分别由独立 fresh context 代理只读审查，全部发现由另一名 fresh implementer 在隔离分支统一修复，提交为 `33c9ce9`。

## Standards

发现三项 P3，均已解决：

1. 请求模型集中在独立 controller 容器且缺 `Request` 后缀。已将共用 `MaskingPreviewRequest` 移到 `model`，单控制器请求嵌入所属 Controller；JSON 字段、Bean Validation 与安全 `toString()` 保留。
2. `DataSecurityMapper.xml` 长 SQL 未按子句分行。已按 SELECT/FROM/WHERE/ORDER BY 等手工换行；审查代理将全部 12 条语句归一化空白后比较，确认语义未变。
3. ResponseAdvice、架构规则与 HTTP 测试存在不必要的全限定类型引用。已改显式 import 与简单类名。

来源规则为 `docs/standards/business-service.md` 的模型位置、接口模型命名、SQL 分行与类型引用约定。基线 smell 发现为 0。

复核结论：原三项全部关闭，无遗留。原报告及复核记录位于 `/private/tmp/field-masking-review-standards.md`、`/private/tmp/field-masking-review-standards-recheck.md`。

## Spec

发现一项 P1，已解决：默认 mapper 预先把响应转为 JSON 树，丢失 HTTP converter 的 view/filter/type 上下文。已用真实 advice 与 converter 复现原本隐藏的 `privateNote` 因 `@JsonView` 或动态 FilterProvider 失效而重新出现，以及声明 `List<Animal>` 的多态类型标识丢失。这违反规格「未声明的字段保持原样」。

修复保留实际 converter 原对象及 advice/hints 链，JSON advice 只启用选择性响应字节缓冲，原样返回 body；成功后提交完整字节，转换失败先清空缓冲并继续使用应用已有异常解析器。没有重新创建 mapper/writer，也没有改变 converter class identity。六项公开 HTTP 转换边界测试覆盖原三组问题、专用 mapper/媒体类型/writer 定制、非 JSON/流式与异步 JSON。

复核结论：原 P1 已关闭，view、filter、声明类型及转换器配置保留，无遗留。报告及复核记录位于 `/private/tmp/field-masking-review-spec.md`、`/private/tmp/field-masking-review-spec-recheck.md`。

## 全量回归补充

首轮 `backend verify` 的 demo 存储测试出现 403 后空指针。新用例为固定 Membership/version 发布了不同权限，随机 Tenant 无法隔离按 Membership/version 缓存的权限，导致测试串扰。改为独立 Membership，在线、导出、轮询与下载沿用同一测试身份；生产缓存和授权未改。

原序列化三组与存储整类均先复现红灯，再修复转绿。专项证据与命令见 `/private/tmp/field-masking-review-fixes.md`。最终后端完整 `verify` 为 1,124 项测试全通过，详见 [集成验收](acceptance.md)。
