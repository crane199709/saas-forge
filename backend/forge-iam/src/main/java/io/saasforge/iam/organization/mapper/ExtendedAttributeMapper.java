package io.saasforge.iam.organization.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.organization.model.ExtendedAttributeRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Extended Attribute 定义。所有语句都按调用方传入的 Token 租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface ExtendedAttributeMapper {
    /** 租户的全部未删除定义，按编码排序。 */
    List<ExtendedAttributeRow> list(@Param("tenantId") UUID tenantId);

    /** 按主键查找未删除的定义；不属于该租户时返回 null。 */
    ExtendedAttributeRow find(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** 插入定义；编码已被未删除的定义占用时不插入并返回 0。 */
    int insert(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("type") String type,
            @Param("required") boolean required,
            @Param("actor") UUID actor);

    /** 修改名称与是否必填；编码与类型不可改。返回受影响行数。 */
    int update(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("required") boolean required,
            @Param("actor") UUID actor);

    /** 逻辑删除，返回受影响行数。 */
    int delete(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("actor") UUID actor);
}
