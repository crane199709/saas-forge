# 统一使用 saas.forge 命名

按项目维护者的明确决定，仓库内原有连写品牌标识统一拆分为 `saas.forge`。Java 包名使用 `io.saas.forge.*`，源码目录对应 `io/saas/forge/`；同时更新 Protobuf 命名空间、事件类型、Schema URL、配置键、Redis Key、Kafka Topic、Docker 标签、域名、脚本和文档。本决策替代 ADR 0012 中保留原 Java 包名的规则；Maven groupId 仍为 `io.github.crane0927`（该坐标值已由 [ADR 0053](0053-maven-groupid-follows-github-account.md) 更新为 `io.github.crane199709`），制品 artifactId 不变。

维护者明确授权重建现有契约基线以采用新名称。该变更破坏旧 Java import、gRPC 服务名及运行配置标识的兼容性，不提供旧命名兼容层；SDK 使用方需更新 import 并重新编译。历史验收文档中的名称同步更新，原有测试结果仅代表当时执行，不表示已按新名称重新验收。

本次只修改仓库制品与模板，不迁移正在使用的数据库、Redis、Kafka、Nacos 或本机证书。现有环境切换需单独安排配置发布、数据与消息处理、证书和 hosts 更新及服务重启。CI 仅对本次授权的基线目录树哈希变更设置精确例外，其他基线修改、删除与重命名继续拒绝。
