# 本地分层验证

后端使用 JDK 17。普通改动先执行受影响模块及必要消费者检查：

```bash
./mvnw -pl saas-forge-services/iam-service -am test
./mvnw -pl saas-forge-services/iam-service -am verify
```

完整后端门禁为 `./mvnw verify`，保留单元/集成、契约兼容、迁移、架构和覆盖率检查，不再调用前端工作区。历史 `-Pbackend-local` 名称保留为兼容别名，不改变检查范围。Testcontainers 测试需要 Docker；跳过测试不能报告为通过。

后端工具脚本使用 Node 24，执行 `node --test --test-concurrency=1 scripts/test/*.test.mjs`。配置和环境变更按现有专项脚本验证；Nacos 仍需 revision 与 `bash scripts/validate-nacos-config.sh`，Compose 布局执行 `python3 scripts/validate-compose-layout.py`。完整 CI 还保留 Tenant 生命周期 Fresh Compose 以及 Nacos 权限/故障恢复。

正式 Client 独立构建和发布仍在 OpenAPI 模块的 `typescript-client/`，通过固定版本提供给前端。前端在 `saas-forge-web` 执行其类型、lint、测试、构建及真实 Chrome 验收，不接管后端环境。

旧浏览器、安全、品牌、国际化、视觉与业务验收代码已备份，具体归属与未迁缺口见[迁出记录](acceptance/consoles-extraction.md)。涉及认证、Cookie、CORS、CSRF、权限或跨服务契约时，仍需对应的后端检查和前端真实组合证据；本仓 CI 绿色不能替代这些尚待交接的浏览器门禁。#206 与各业务票继续跟踪，不据此关闭父 #201 或 #183–#189。
