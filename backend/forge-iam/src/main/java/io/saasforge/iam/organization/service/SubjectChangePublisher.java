package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.service.MembershipNotFoundException;
import io.saasforge.iam.membership.service.MembershipStateService;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 组织数据变化改变了成员的主体属性时递增其 version，旧 Token 立即失效，刷新后业务服务读到新的主体属性。
 *
 * <p>只能在组织数据的事务提交之后调用：版本先于数据提交发布到 Redis，事务一旦回滚，Redis 中的版本就领先于数据库，
 * 而 Redis 只允许版本前进，按数据库版本签发的 Token 将一直被网关拒绝。
 */
@Service
class SubjectChangePublisher {
    private final MembershipStateService membershipStates;

    SubjectChangePublisher(MembershipStateService membershipStates) {
        this.membershipStates = membershipStates;
    }

    void changed(Collection<UUID> membershipIds) {
        for (UUID membershipId : membershipIds) {
            try {
                membershipStates.incrementVersion(membershipId);
            } catch (MembershipNotFoundException ignored) {
                // 成员在组织变更提交后被删除，不再需要发布。
            }
        }
    }
}
