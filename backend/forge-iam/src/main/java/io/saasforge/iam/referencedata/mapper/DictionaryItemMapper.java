package io.saasforge.iam.referencedata.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.referencedata.model.DictionaryItemRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户对字典项的设置与自定义项。所有语句都按调用方传入的租户限定，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface DictionaryItemMapper {
    /** 在当前事务内串行化同一租户同一字典的修改，使名称唯一检查与写入之间不被并发修改穿插；事务结束时释放。 */
    int lockDictionary(
            @Param("tenantId") UUID tenantId, @Param("service") String service, @Param("dictionary") String dictionary);

    /** 租户的全部未删除设置。 */
    List<DictionaryItemRow> list(@Param("tenantId") UUID tenantId);

    /** 插入自定义项；编码已被占用时不插入并返回 0。 */
    int insertCustom(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("dictionary") String dictionary,
            @Param("code") String code,
            @Param("name") String name,
            @Param("sortOrder") int sortOrder,
            @Param("actor") UUID actor);

    /** 设置显示名与排序；内置项还没有设置行时新建。返回受影响行数。 */
    int saveSettings(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("dictionary") String dictionary,
            @Param("code") String code,
            @Param("custom") boolean custom,
            @Param("name") String name,
            @Param("sortOrder") int sortOrder,
            @Param("actor") UUID actor);

    /**
     * 停用或启用；内置项还没有设置行时新建，显示名与排序为 null 即沿用声明。{@code name}、{@code sortOrder} 只在新建时写入，
     * 自定义项总有行，传入当前值以满足表约束。返回受影响行数。
     */
    int saveEnabled(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("dictionary") String dictionary,
            @Param("code") String code,
            @Param("custom") boolean custom,
            @Param("name") String name,
            @Param("sortOrder") Integer sortOrder,
            @Param("enabled") boolean enabled,
            @Param("actor") UUID actor);

    /** 租户删除时逻辑删除其全部设置；无操作人，updated_by 写 NULL。返回受影响行数。 */
    int deleteTenant(@Param("tenantId") UUID tenantId);
}
