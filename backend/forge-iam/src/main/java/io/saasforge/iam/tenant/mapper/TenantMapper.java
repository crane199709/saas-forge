package io.saasforge.iam.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
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
}
