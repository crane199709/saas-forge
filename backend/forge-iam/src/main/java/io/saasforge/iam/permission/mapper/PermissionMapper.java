package io.saasforge.iam.permission.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.permission.model.PermissionRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface PermissionMapper {
    /**
     * 新增权限码，或更新本服务已有权限码的名称、分组、Realm 并恢复为 active。编码已归属其他服务时不修改，返回 0。
     * 权限码是全局表，不受 Data Policy 过滤。
     *
     * @param realm 申报服务所属的 Realm：{@code tenant} 或 {@code platform}
     */
    int upsert(
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("service") String service,
            @Param("name") String name,
            @Param("group") String group,
            @Param("realm") String realm);

    /** 把该服务不在 {@code codes} 中的 active 权限码标记为 inactive，返回受影响行数。不受 Data Policy 过滤。 */
    int deactivateMissing(@Param("service") String service, @Param("codes") Collection<String> codes);

    /** 该 Realm 的全部 active 权限码，按编码排序。不受 Data Policy 过滤。 */
    List<String> activeCodes(@Param("realm") String realm);

    /** 该 Realm 的全部 active 权限码及其展示信息，按服务、分组、编码排序。不受 Data Policy 过滤。 */
    List<PermissionRow> activePermissions(@Param("realm") String realm);
}
