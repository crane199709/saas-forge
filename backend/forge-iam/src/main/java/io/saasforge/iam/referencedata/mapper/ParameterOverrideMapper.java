package io.saasforge.iam.referencedata.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.referencedata.model.ParameterOverrideRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户对 Parameter 的覆盖值。所有语句都按调用方传入的租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface ParameterOverrideMapper {
    /** 租户的全部未删除覆盖值，含服务新版本已删除的参数上保留的值。 */
    List<ParameterOverrideRow> list(@Param("tenantId") UUID tenantId);

    /** 写入覆盖值，已有覆盖值时替换。{@code value} 为 JSON。 */
    int save(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("code") String code,
            @Param("value") String value,
            @Param("actor") UUID actor);

    /** 恢复默认值：逻辑删除覆盖值，返回受影响行数（没有覆盖值时为 0）。 */
    int delete(
            @Param("tenantId") UUID tenantId,
            @Param("service") String service,
            @Param("code") String code,
            @Param("actor") UUID actor);

    /** 租户删除时逻辑删除其全部覆盖值；无操作人，updated_by 写 NULL。返回受影响行数。 */
    int deleteTenant(@Param("tenantId") UUID tenantId);
}
