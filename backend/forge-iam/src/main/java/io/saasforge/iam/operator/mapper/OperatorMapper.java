package io.saasforge.iam.operator.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// iam 的表不按租户行拦截隔离（ADR-0001），租户条件由 SQL 显式书写；消费事件时 SDK 恢复的租户上下文不能改写这些 SQL。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface OperatorMapper {
    /** 按账号定位未删除的 active Operator 的凭证；Operator 只支持账号登录，不接受邮箱。Operator 不属于租户，不受 Data Policy 过滤。 */
    OperatorCredential findCredential(@Param("account") String account);

    /** 签发与刷新 Platform Realm Token 时重新确认 Operator 未删除且仍为 active，不受 Data Policy 过滤。 */
    ActiveOperator findActive(@Param("operatorId") UUID operatorId);
}
