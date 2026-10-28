package io.saasforge.platform.tenant.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用：不等待真实的冷静期，直接让它结束。 */
@Mapper
public interface CoolingOffMapper {
    /** 把租户的冷静期结束时间改到过去。平台全局表，不受 Data Policy 过滤。 */
    void endCoolingOff(@Param("tenantId") UUID tenantId);
}
