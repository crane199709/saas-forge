package io.saasforge.iam.role.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoleMapper {
    /** 为租户插入一个预置角色，已存在同编码的未删除角色时不做修改。按租户定位，不受 Data Policy 过滤。 */
    int insertBuiltin(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("code") String code,
            @Param("name") String name);

    /** Membership 拥有的未删除角色编码，按编码排序。用于发布权限明细，不受 Data Policy 过滤。 */
    List<String> roleCodes(@Param("membershipId") UUID membershipId);
}
