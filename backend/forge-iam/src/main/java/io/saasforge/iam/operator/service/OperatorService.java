package io.saasforge.iam.operator.service;

import io.saasforge.iam.operator.mapper.OperatorMapper;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 其他功能包只能经由本服务读取 Operator 数据，不直接注入 OperatorMapper。
@Service
public class OperatorService {
    private final OperatorMapper operators;

    OperatorService(OperatorMapper operators) {
        this.operators = operators;
    }

    public OperatorCredential findCredential(String account) {
        return operators.findCredential(account);
    }

    public ActiveOperator findActive(UUID operatorId) {
        return operators.findActive(operatorId);
    }
}
