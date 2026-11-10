package io.saasforge.iam.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface TenantMapper {
    /**
     * 应用 platform 广播的状态变更：租户已登记且已应用的版本更旧时更新，未登记时按该状态登记。租户表是全局表，不受 Data Policy 过滤。
     *
     * @return 应用了变更为 1；已应用同一或更新的版本时为 0
     */
    int applyStatus(
            @Param("tenantId") UUID tenantId,
            @Param("name") String name,
            @Param("status") String status,
            @Param("version") long version);

    /** 登记 platform 创建的租户，已存在时不做修改。租户表是全局表，不受 Data Policy 过滤。 */
    void insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("name") String name, @Param("status") String status);

    /** 按状态和 ID 升序查询未删除的租户 ID；租户目录是全局表，不受 Data Policy 过滤。 */
    List<UUID> findIdsByStatus(
            @Param("statuses") List<String> statuses, @Param("offset") long offset, @Param("limit") int limit);

    /** 匹配状态的未删除租户总数，与分页查询使用相同条件；租户目录是全局表，不受 Data Policy 过滤。 */
    long countByStatus(@Param("statuses") List<String> statuses);

    /** 租户名称；未登记或已删除时为 null。租户表是全局表，不受 Data Policy 过滤。 */
    String findName(@Param("tenantId") UUID tenantId);

    /** 租户是否提出 MFA Requirement；未登记或已删除时为 false。租户表是全局表，不受 Data Policy 过滤。 */
    boolean findMfaRequired(@Param("tenantId") UUID tenantId);

    /**
     * 设置租户的 MFA Requirement。租户表是全局表，不受 Data Policy 过滤。
     *
     * @param actor 操作者的 Identity，写入 {@code updated_by}
     * @return 租户已登记且未删除时为 1，否则为 0
     */
    int updateMfaRequired(
            @Param("tenantId") UUID tenantId, @Param("mfaRequired") boolean mfaRequired, @Param("actor") UUID actor);
    /** 租户的 IP 允许列表发布值；不存在或已删除时为 null。全局表，不受 Data Policy 过滤。 */
    String findIpAllowlist(@Param("tenantId") UUID tenantId);

    /** 更新 IP 允许列表并持有租户行锁直到事务结束；全局表，不受 Data Policy 过滤。 */
    int updateIpAllowlist(@Param("tenantId") UUID tenantId, @Param("rules") String rules, @Param("actor") UUID actor);
    /** 锁定未删除租户并读取 IP 允许列表，供恢复发布与修改串行；全局表，不受 Data Policy 过滤。 */
    String lockIpAllowlist(@Param("tenantId") UUID tenantId);

    /** 列出所有未删除租户，供启动时恢复发布；全局表，不受 Data Policy 过滤。 */
    List<UUID> findAllIds();

    /** 租户的 Navigation Layout JSON 文本；未设置、未登记或已删除时为 null。全局表，不受 Data Policy 过滤。 */
    String findNavigationLayout(@Param("tenantId") UUID tenantId);

    /**
     * 替换租户的 Navigation Layout，{@code layout} 为 null 时恢复默认。全局表，不受 Data Policy 过滤。
     *
     * @return 受影响行数，租户未登记或已删除时为 0
     */
    int updateNavigationLayout(
            @Param("tenantId") UUID tenantId, @Param("layout") String layout, @Param("actor") UUID actor);
}
