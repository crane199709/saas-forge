package io.saasforge.platform.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.platform.tenant.entity.Tenant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
    /** 一页未删除的租户，按标识（UUIDv7）倒序即最近创建的在前。平台全局表，不受 Data Policy 过滤。 */
    List<Tenant> page(@Param("offset") long offset, @Param("limit") int limit);
}
