package io.saas.forge.iam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.saas.forge.iam.api.PlatformAdministratorsController;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.authorization.PlatformAdminGrantService;
import io.saas.forge.iam.application.authorization.PlatformAdminQueries;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeService;
import io.saas.forge.iam.application.client.OAuthClientManagementAuthorizer;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class PlatformAdminManagementConfigurationTest {
    @Test
    void productionConfigurationSuppliesEveryPlatformAdministratorControllerDependency() {
        new ApplicationContextRunner()
                .withUserConfiguration(PlatformAdminManagementConfiguration.class,
                        PlatformAdministratorsController.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(IdentityRepository.class, () -> mock(IdentityRepository.class))
                .withBean(PlatformRoleAssignmentRepository.class,
                        () -> mock(PlatformRoleAssignmentRepository.class))
                .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
                .withBean(PasswordSetupDeliveryService.class, () -> mock(PasswordSetupDeliveryService.class))
                .withBean(RevocationIndex.class, () -> mock(RevocationIndex.class))
                .withBean(OAuthClientManagementAuthorizer.class,
                        () -> mock(OAuthClientManagementAuthorizer.class))
                .withBean(UuidV7Generator.class, () -> mock(UuidV7Generator.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformAdminGrantService.class);
                    assertThat(context).hasSingleBean(PlatformAdminQueries.class);
                    assertThat(context).hasSingleBean(PlatformAdminRevokeService.class);
                    assertThat(context).hasSingleBean(PlatformAdministratorsController.class);
                });
    }
}
