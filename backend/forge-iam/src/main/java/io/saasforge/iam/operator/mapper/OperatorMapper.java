package io.saasforge.iam.operator.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import io.saasforge.iam.operator.model.OperatorPassword;
import io.saasforge.iam.operator.model.OperatorRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface OperatorMapper {
    /** 按账号定位未删除的 active Operator 的凭证；Operator 只支持账号登录，不接受邮箱。Operator 不属于租户，不受 Data Policy 过滤。 */
    OperatorCredential findCredential(@Param("account") String account);

    /** 签发与刷新 Platform Realm Token 时重新确认 Operator 未删除且仍为 active，并取得当前版本与负责区域。Operator 不属于租户，不受 Data Policy 过滤。 */
    ActiveOperator findActive(@Param("operatorId") UUID operatorId);

    /** 未删除的 active Operator 的账号与密码哈希，供自助改密码；不存在时为 null。Operator 不属于租户，不受 Data Policy 过滤。 */
    OperatorPassword findActivePassword(@Param("operatorId") UUID operatorId);

    /** 全部未删除的 Operator，按账号排序。Operator 不属于租户，不受 Data Policy 过滤。 */
    List<OperatorRow> list();

    /** 未删除的 Operator；不存在时为 null。Operator 不属于租户，不受 Data Policy 过滤。 */
    OperatorRow find(@Param("id") UUID id);

    /** 新建 active 的 Operator；账号已被未删除的 Operator 占用时不插入，返回 0。Operator 不属于租户，不受 Data Policy 过滤。 */
    int insert(
            @Param("id") UUID id,
            @Param("account") String account,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash,
            @Param("region") String region,
            @Param("actor") UUID actor);

    /** 修改显示名与负责区域，返回受影响行数。Operator 不属于租户，不受 Data Policy 过滤。 */
    int update(
            @Param("id") UUID id,
            @Param("displayName") String displayName,
            @Param("region") String region,
            @Param("actor") UUID actor);

    /** 状态与给定值不同时改为给定值，返回受影响行数；已是该状态时为 0。Operator 不属于租户，不受 Data Policy 过滤。 */
    int changeStatus(@Param("id") UUID id, @Param("status") String status, @Param("actor") UUID actor);

    /** 替换密码哈希，返回受影响行数。Operator 不属于租户，不受 Data Policy 过滤。 */
    int changePassword(@Param("id") UUID id, @Param("passwordHash") String passwordHash, @Param("actor") UUID actor);

    /** 递增版本并返回新版本；Operator 不存在时为 null。版本是安全状态而不是业务修改，不刷新 updated_*。Operator 不属于租户，不受 Data Policy 过滤。 */
    Long incrementVersion(@Param("id") UUID id);
}
