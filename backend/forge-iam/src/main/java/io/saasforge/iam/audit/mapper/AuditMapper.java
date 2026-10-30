package io.saasforge.iam.audit.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.audit.model.AuditQuery;
import io.saasforge.iam.audit.model.AuditRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 按月分区的审计表 audit_event。Realm 与租户条件由 SQL 显式书写，均不受 Data Policy 过滤。 */
// 同 TenantPlanMapper：消费事件时 SDK 恢复的租户上下文不能改写这些 SQL，Platform Realm 事件本就没有租户。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface AuditMapper {
    /**
     * 确保某月的分区存在，经迁移账号所有的 SECURITY DEFINER 函数创建，已存在时为无操作。
     *
     * @param month 该月的第一天（UTC）
     */
    void ensurePartition(@Param("month") LocalDate month);

    /**
     * 写入一条事件；同一事件重复送达时（主键相同）忽略。
     *
     * @param realm {@code tenant} 或 {@code platform}
     * @param category {@code login}、{@code permission} 或 {@code operation}
     * @param outcome {@code success} 或 {@code failure}
     * @param detail 补充信息的 JSON 文本
     */
    void insert(
            @Param("id") UUID id,
            @Param("occurredAt") OffsetDateTime occurredAt,
            @Param("realm") String realm,
            @Param("tenantId") UUID tenantId,
            @Param("actorId") UUID actorId,
            @Param("category") String category,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("outcome") String outcome,
            @Param("errorCode") String errorCode,
            @Param("detail") String detail,
            @Param("service") String service,
            @Param("traceId") String traceId);

    /**
     * 一页审计记录，按发生时间倒序。Tenant Realm 只含指定租户的事件，操作人取自 Identity；Platform Realm 操作人取自 Operator。
     *
     * @param realm {@code tenant} 或 {@code platform}
     * @param tenantId Tenant Realm 时必填，Platform Realm 时为 null
     */
    List<AuditRecord> search(
            @Param("realm") String realm,
            @Param("tenantId") UUID tenantId,
            @Param("query") AuditQuery query,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 与 {@link #search} 条件相同的记录总数。 */
    long count(@Param("realm") String realm, @Param("tenantId") UUID tenantId, @Param("query") AuditQuery query);
}
