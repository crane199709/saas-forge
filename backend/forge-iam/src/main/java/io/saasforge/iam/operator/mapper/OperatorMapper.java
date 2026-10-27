package io.saasforge.iam.operator.mapper;

import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OperatorMapper {
    /** 按账号定位 active Operator 的凭证；Operator 只支持账号登录，不接受邮箱。Operator 不属于租户，不受 Data Policy 过滤。 */
    OperatorCredential findCredential(@Param("account") String account);

    /** 签发与刷新 Platform Realm Token 时重新确认 Operator 仍为 active，不受 Data Policy 过滤。 */
    ActiveOperator findActive(@Param("operatorId") UUID operatorId);
}
