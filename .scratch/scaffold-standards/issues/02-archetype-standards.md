# 02: archetype 带入规范

**What to build:** `forge-service-archetype` 生成的工程带上 `AGENTS.md` 与 `docs/standards/business-service.md`，补齐通用写法，并增加 SDK schema 检查。见规格第 2、3、4 节。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 构建时把 `docs/standards/business-service.md` 复制进模板资源，不在 archetype 里另存一份
- [ ] 模板新增 `AGENTS.md`：规则摘要、`./mvnw verify` 等命令、指向 `docs/standards/` 的链接
- [ ] `archetype-metadata.xml` 中 Markdown 文件 `filtered="false"`；文档头部写入平台版本
- [ ] 按规格第 3 节对比模板与 demo-service，补齐通用写法（至少请求体 `@Valid` 校验）；业务示范不进模板。在本工单 Answer 中列出对比结论与补齐项
- [ ] 生成工程增加检查：POM 中每个带 `schema.sql` 的 SDK starter，迁移后其中的表都存在
- [ ] `archetype-it` 断言 `AGENTS.md` 与文档存在、标题未被吞掉、版本已写入
- [ ] `./mvnw -o -pl forge-service-archetype -am install -Parchetype-it` 通过
