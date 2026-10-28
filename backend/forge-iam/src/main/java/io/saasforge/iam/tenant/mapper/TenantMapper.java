package io.saasforge.iam.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface TenantMapper {
    /** 修改未删除租户的状态，返回受影响行数。租户表是全局表，不受 Data Policy 过滤。 */
    int updateStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);

    /** 登记 platform 创建的租户，已存在时不做修改。租户表是全局表，不受 Data Policy 过滤。 */
    void insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("name") String name, @Param("status") String status);
}
