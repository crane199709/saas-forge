package io.saasforge.platform.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.platform.tenant.entity.TenantInitialization;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantInitializationMapper extends BaseMapper<TenantInitialization> {
    /**
     * 记录服务回报的初始化结果；未预建该服务的状态行时新增。回报由消费者写入，没有操作人。平台全局表，不受 Data Policy 过滤。
     *
     * @param id 新增时使用的标识
     * @param status {@code succeeded} 或 {@code failed}
     * @param error 失败原因，成功时为 null
     */
    void report(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("service") String service,
            @Param("status") String status,
            @Param("error") String error);
}
