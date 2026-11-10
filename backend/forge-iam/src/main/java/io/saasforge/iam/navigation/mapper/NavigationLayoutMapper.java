package io.saasforge.iam.navigation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台控制台的 Navigation Layout，布局以 JSON 文本读写；租户布局存在租户表，经由 {@code TenantService} 读写。全局表，不受 Data Policy 过滤。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface NavigationLayoutMapper {
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
