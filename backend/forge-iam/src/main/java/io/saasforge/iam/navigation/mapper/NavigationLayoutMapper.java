package io.saasforge.iam.navigation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 两端的 Navigation Layout，布局以 JSON 文本读写。租户布局按调用方传入的租户限定，平台布局是全局的；都不受 Data Policy 过滤。
 */
// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface NavigationLayoutMapper {
    /** 租户的布局；未设置、租户未登记或已删除时为 null。 */
    String findTenantLayout(@Param("tenantId") UUID tenantId);

    /**
     * 替换租户的布局，{@code layout} 为 null 时恢复默认。
     *
     * @return 受影响行数，租户未登记或已删除时为 0
     */
    int updateTenantLayout(
            @Param("tenantId") UUID tenantId, @Param("layout") String layout, @Param("actor") UUID actor);

    /** 在当前事务内串行化平台布局的保存与恢复默认，维持最多一条未删除记录。 */
    int lockPlatform();

    /** 平台的布局；没有未删除记录时为 null。 */
    String findPlatformLayout();

    /** 替换未删除的平台布局，返回受影响行数，没有时为 0。 */
    int updatePlatformLayout(@Param("layout") String layout, @Param("actor") UUID actor);

    /** 新增平台布局，调用方须已持有 {@link #lockPlatform()} 并确认没有未删除记录。 */
    int insertPlatformLayout(@Param("id") UUID id, @Param("layout") String layout, @Param("actor") UUID actor);

    /** 逻辑删除平台布局，即恢复默认，返回受影响行数。 */
    int deletePlatformLayout(@Param("actor") UUID actor);
}
