package io.saasforge.platform.ops.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface DeadEventFixtureMapper {
    void capture(
            @Param("id") UUID id,
            @Param("tenant") UUID tenant,
            @Param("originalTenant") String originalTenant,
            @Param("tag") String tag,
            @Param("payload") byte[] payload);
}
