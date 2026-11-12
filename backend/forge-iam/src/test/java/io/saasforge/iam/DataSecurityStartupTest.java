package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.api.ForgeException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 通过真实 IAM 应用启动验证破损的持久化引用会阻止服务就绪。 */
class DataSecurityStartupTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @Test
    void startupIgnoresHistoricalOverridesOfRetiredFields() {
        var retiredDatabase = SharedContainers.postgres();
        try (var context = new SpringApplicationBuilder(IamApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + retiredDatabase.getJdbcUrl(),
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--spring.data.redis.database=" + redis.database(),
                        "--forge.iam.signing-key-file=target/test-retired-masking-signing.jwk",
                        "--spring.flyway.locations=classpath:db/migration,classpath:masking-retired")) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    void startupRejectsMissingPersistedGradeDefaultRule() {
        assertThatThrownBy(() -> {
                    try (var ignored = new SpringApplicationBuilder(IamApplication.class)
                            .run(
                                    "--server.port=0",
                                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "--spring.data.redis.host=" + redis.getHost(),
                                    "--spring.data.redis.port=" + redis.getMappedPort(6379),
                                    "--spring.data.redis.database=" + redis.database(),
                                    "--forge.iam.signing-key-file=target/test-broken-masking-signing.jwk",
                                    "--spring.flyway.locations=classpath:db/migration,classpath:masking-broken")) {}
                })
                .isInstanceOf(ForgeException.class)
                .hasMessage("data-security.invalidReference");
    }
}
