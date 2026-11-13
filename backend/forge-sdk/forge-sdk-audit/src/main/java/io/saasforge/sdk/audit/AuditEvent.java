package io.saasforge.sdk.audit;

import io.saasforge.sdk.core.UuidV7;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条 Audit Event：谁（{@code actorId}）在哪个租户（{@code tenantId}）对什么（{@code resourceType}/{@code resourceId}）
 * 做了什么（{@code action}）、结果如何（{@code outcome}/{@code errorCode}）。经 {@link AuditRecorder} 发布到 {@link #TOPIC}。
 *
 * <p>消息可能重复送达，订阅方按 {@code id} 去重；{@code occurredAt} 决定写入哪个月的分区。
 *
 * @param id 事件 ID（UUIDv7）
 * @param occurredAt 动作发生时间
 * @param realm 所属 Realm
 * @param tenantId 所在租户；Platform Realm 事件，以及尚未确定租户的登录（如登录失败、退出）为 null
 * @param actorId 操作人：Tenant Realm 为 Identity id，Platform Realm 为 Operator id；无法确定（如未知账号登录失败、系统操作）为 null
 * @param category 事件类别
 * @param action 动作，点分、各段 lowerCamelCase，如 {@code order.create}、{@code auth.login}
 * @param resourceType 操作对象类型，如 {@code order}；没有具体对象时为 null
 * @param resourceId 操作对象标识；无法确定时为 null
 * @param outcome 结果
 * @param errorCode 失败时的错误码，如 {@code order.notFound}；成功时为 null
 * @param detail 补充信息，如授予的权限码；不得含密码、Token 等敏感内容
 * @param service 产出事件的服务，取 {@code spring.application.name}，由 {@link AuditRecorder} 填写
 * @param traceId 产出事件时的 traceId，由 {@link AuditRecorder} 填写，没有时为 null
 */
public record AuditEvent(
        UUID id,
        Instant occurredAt,
        AuditRealm realm,
        UUID tenantId,
        UUID actorId,
        AuditCategory category,
        String action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        String errorCode,
        Map<String, Object> detail,
        String service,
        String traceId) {
    /** Audit Event 所在的 Topic。 */
    public static final String TOPIC = "forge-audit";

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        // 保持产出方书写的顺序；反序列化得到的值可能含 null，不用 Map.copyOf
        detail = detail == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    /**
     * 开始构建一条现在发生的事件，默认结果为成功。
     *
     * @param realm 所属 Realm
     * @param category 事件类别
     * @param action 动作，点分、各段 lowerCamelCase
     * @return 构建器
     */
    public static Builder builder(AuditRealm realm, AuditCategory category, String action) {
        return new Builder(realm, category, action);
    }

    /** 填入产出方的服务名与 traceId；只由 {@link AuditRecorder} 调用，业务代码不自行填写。 */
    AuditEvent withSource(String service, String traceId) {
        return new AuditEvent(
                id,
                occurredAt,
                realm,
                tenantId,
                actorId,
                category,
                action,
                resourceType,
                resourceId,
                outcome,
                errorCode,
                detail,
                service,
                traceId);
    }

    /** {@link AuditEvent} 的构建器，ID 与发生时间在创建构建器时确定。 */
    public static final class Builder {
        private final UUID id = UuidV7.generate();
        private final Instant occurredAt = Instant.now();
        private final AuditRealm realm;
        private final AuditCategory category;
        private final String action;
        private UUID tenantId;
        private UUID actorId;
        private String resourceType;
        private String resourceId;
        private AuditOutcome outcome = AuditOutcome.SUCCESS;
        private String errorCode;
        // 保持插入顺序，查看审计时字段顺序与产出方书写一致
        private final Map<String, Object> detail = new LinkedHashMap<>();

        private Builder(AuditRealm realm, AuditCategory category, String action) {
            this.realm = realm;
            this.category = category;
            this.action = action;
        }

        /**
         * 设置所在租户。
         *
         * @param tenantId 租户 ID，可为 null
         * @return 本构建器
         */
        public Builder tenant(UUID tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /**
         * 设置操作人。
         *
         * @param actorId Identity id 或 Operator id，可为 null
         * @return 本构建器
         */
        public Builder actor(UUID actorId) {
            this.actorId = actorId;
            return this;
        }

        /**
         * 设置操作对象。
         *
         * @param type 对象类型
         * @param id 对象标识，可为 null
         * @return 本构建器
         */
        public Builder resource(String type, Object id) {
            this.resourceType = type;
            this.resourceId = id == null ? null : id.toString();
            return this;
        }

        /**
         * 标记为失败。
         *
         * @param errorCode 错误码
         * @return 本构建器
         */
        public Builder failure(String errorCode) {
            this.outcome = AuditOutcome.FAILURE;
            this.errorCode = errorCode;
            return this;
        }

        /**
         * 添加一项补充信息，值为 null 时忽略。
         *
         * @param key 键
         * @param value 可序列化为 JSON 的值，不得含敏感内容
         * @return 本构建器
         */
        public Builder detail(String key, Object value) {
            if (value != null) {
                detail.put(key, value);
            }
            return this;
        }

        /**
         * 构建事件；服务名与 traceId 在记录时由 {@link AuditRecorder} 填写。
         *
         * @return 事件
         */
        public AuditEvent build() {
            return new AuditEvent(
                    id,
                    occurredAt,
                    realm,
                    tenantId,
                    actorId,
                    category,
                    action,
                    resourceType,
                    resourceId,
                    outcome,
                    errorCode,
                    detail,
                    null,
                    null);
        }
    }
}
