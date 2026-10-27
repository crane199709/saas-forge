package io.saasforge.iam.membership.mapper;

import io.saasforge.iam.membership.model.MemberRow;
import io.saasforge.iam.membership.model.MembershipChange;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MembershipMapper {
    /** 停用 Membership 并递增版本号，返回新版本；不存在或已删除时返回 null。管理操作按主键定位，不受 Data Policy 过滤。 */
    MembershipChange deactivate(@Param("membershipId") UUID membershipId);

    /** 递增版本号（如角色变更），返回新版本；不存在或已删除时返回 null。不受 Data Policy 过滤。 */
    MembershipChange incrementVersion(@Param("membershipId") UUID membershipId);

    /** 租户内未删除的 Membership 是否存在。按 Token 租户限定，不受 Data Policy 过滤。 */
    boolean exists(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 租户内一页未删除的 Membership 及其 Identity 资料，按 Membership 标识（UUIDv7，即大致的创建顺序）排序。不受 Data Policy 过滤。 */
    List<MemberRow> page(@Param("tenantId") UUID tenantId, @Param("offset") long offset, @Param("limit") int limit);

    /** 租户内未删除的 Membership 数量。不受 Data Policy 过滤。 */
    long count(@Param("tenantId") UUID tenantId);

    /** Extended Attribute 值（JSON 对象文本）；不存在时返回 null。不受 Data Policy 过滤。 */
    String attributes(@Param("tenantId") UUID tenantId, @Param("membershipId") UUID membershipId);

    /** 整体替换 Extended Attribute 值，返回受影响行数。按 Token 租户限定，不受 Data Policy 过滤。 */
    int updateAttributes(
            @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId,
            @Param("attributes") String attributes,
            @Param("actor") UUID actor);

    /** 从租户全部 Membership 的 Extended Attribute 值中移除某个编码，返回被修改的 Membership。不受 Data Policy 过滤。 */
    List<UUID> removeAttribute(
            @Param("tenantId") UUID tenantId, @Param("code") String code, @Param("actor") UUID actor);
}
