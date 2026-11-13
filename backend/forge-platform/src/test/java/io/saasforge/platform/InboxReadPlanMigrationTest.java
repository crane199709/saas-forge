package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.plan.service.PlanService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/** 以公开套餐服务验收迁移前 Feature Pack 的升级；事务回滚避免影响共享上下文中的其他套餐。 */
class InboxReadPlanMigrationTest extends PlatformIntegrationTest {
    @Autowired
    PlanService plans;

    @Test
    @Transactional
    @Sql({"/fixtures/inbox-plan-upgrade.sql", "/db/migration/V8__inbox_read_permissions.sql"})
    void existingPlansRetainTheirBusinessPermissionsAndGainExplicitInboxReads() {
        assertThat(plans.get(UUID.fromString("019a0200-0000-7000-8000-0000000000e1"))
                        .getPermissions())
                .containsExactly("order:read", "todo:read", "notification:read", "announcement:read");
    }
}
