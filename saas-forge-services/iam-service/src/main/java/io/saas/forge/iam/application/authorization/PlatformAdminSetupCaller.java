package io.saas.forge.iam.application.authorization;

import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** IAM 的保留 Client 只作为既有 Setup Delivery 表的本地幂等命名空间。 */
public class PlatformAdminSetupCaller {
    private final JdbcTemplate jdbc;

    public PlatformAdminSetupCaller(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID callerClientId() {
        var ids = jdbc.query("""
                SELECT id FROM iam_oauth_clients
                WHERE client_type = 'RESERVED_SERVICE' AND reserved_service_key = 'IAM'
                  AND client_status = 'ACTIVE'
                """, (rs, row) -> rs.getObject("id", UUID.class));
        if (ids.size() != 1) throw new PasswordSetupDeliveryUnavailableException();
        return ids.get(0);
    }
}
