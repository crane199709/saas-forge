package io.saasforge.iam.tenant.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper {
    /** 修改未删除租户的状态，返回受影响行数。租户表是全局表，不受 Data Policy 过滤。 */
    int updateStatus(@Param("tenantId") UUID tenantId, @Param("status") String status);
}
