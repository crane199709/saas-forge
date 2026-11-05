package io.saasforge.iam.operator.service;

import io.saasforge.iam.operator.mapper.OperatorMapper;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import io.saasforge.iam.operator.model.OperatorPassword;
import io.saasforge.iam.operator.model.OperatorRow;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// 其他功能包只能经由本服务读取 Operator 数据，不直接注入 OperatorMapper。权限由调用方校验，版本由调用方在事务提交后递增。
@Service
public class OperatorService {
    /** Operator 的状态：可登录。 */
    public static final String ACTIVE = "active";

    /** Operator 的状态：已停用，不能登录，已签发的 Token 立即失效。 */
    public static final String DISABLED = "disabled";

    private final OperatorMapper operators;
    private final PasswordEncoder passwords;

    OperatorService(OperatorMapper operators, PasswordEncoder passwords) {
        this.operators = operators;
        this.passwords = passwords;
    }

    public OperatorCredential findCredential(String account) {
        return operators.findCredential(account);
    }

    public ActiveOperator findActive(UUID operatorId) {
        return operators.findActive(operatorId);
    }

    /** 未删除的 active Operator 的账号与密码哈希；不存在时为 null。 */
    public OperatorPassword findActivePassword(UUID operatorId) {
        return operators.findActivePassword(operatorId);
    }

    /** 全部未删除的 Operator，按账号排序。 */
    public List<OperatorRow> list() {
        return operators.list();
    }

    /**
     * 未删除的 Operator。
     *
     * @throws ForgeException 404 {@code operator.notFound}
     */
    public OperatorRow get(UUID id) {
        OperatorRow operator = operators.find(id);
        if (operator == null) throw new ForgeException(OperatorErrorCode.NOT_FOUND);
        return operator;
    }

    /**
     * 新建 active 的 Operator，密码以 BCrypt 存储。
     *
     * @throws ForgeException 409 {@code operator.accountTaken}
     */
    public UUID create(String account, String displayName, String password, String region, UUID actor) {
        UUID id = UuidV7.generate();
        if (operators.insert(id, account, displayName, passwords.encode(password), region, actor) == 0) {
            throw new ForgeException(OperatorErrorCode.ACCOUNT_TAKEN, account);
        }
        return id;
    }

    /** @throws ForgeException 404 {@code operator.notFound} */
    public void update(UUID id, String displayName, String region, UUID actor) {
        if (operators.update(id, displayName, region, actor) == 0)
            throw new ForgeException(OperatorErrorCode.NOT_FOUND);
    }

    /**
     * 改为给定状态。
     *
     * @return 状态是否发生变化；已是该状态时为 false
     */
    public boolean changeStatus(UUID id, String status, UUID actor) {
        return operators.changeStatus(id, status, actor) == 1;
    }

    /** @throws ForgeException 404 {@code operator.notFound} */
    public void changePassword(UUID id, String password, UUID actor) {
        if (operators.changePassword(id, passwords.encode(password), actor) == 0) {
            throw new ForgeException(OperatorErrorCode.NOT_FOUND);
        }
    }
}
