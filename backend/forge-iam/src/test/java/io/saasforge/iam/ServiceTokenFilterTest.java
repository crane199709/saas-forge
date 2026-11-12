package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.iam.servicecredential.service.ServiceCredentialAuthentication;
import io.saasforge.iam.servicecredential.service.ServiceCredentialService;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 凭证生命周期经内部 HTTP 认证边界验证，数据库使用真实 PostgreSQL。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceTokenFilterTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();
    static final UUID ACTOR = UUID.fromString("019a0110-0000-7000-8000-0000000000f1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        registry.add("forge.iam.signing-key-file", () -> "target/test-credentials-signing.jwk");
        registry.add("forge.iam.manifest.tokens.legacy-service", () -> "legacy-service-token");
        registry.add("forge.iam.manifest.tokens.shadow-service", () -> "shadow-token");
    }

    @LocalServerPort
    int port;

    @Autowired
    ServiceCredentialService credentials;

    @Autowired
    OutboxAuditMapper outbox;

    @MockitoSpyBean
    DataSource dataSource;

    @Test
    void twoCredentialsAuthenticateIndependentlyAndDisablingTakesEffectOnTheNextRequest() {
        var first = credentials.create("credential-service", ACTOR);
        var second = credentials.create("credential-service", ACTOR);
        assertThat(get(first.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(get(second.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(credentials.list("credential-service")).hasSize(2).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("active");
            assertThat(row.lastUsedAt()).isNotNull();
        });
        credentials.disable(first.credential().id(), ACTOR);
        assertThat(get(first.token()).getStatusCode().value()).isEqualTo(401);
        assertThat(get(second.token()).getStatusCode().value()).isEqualTo(200);
        credentials.disable(second.credential().id(), ACTOR);
        assertThat(get(second.token()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void immediateRotationDisablesOldTokenAndAuditsTheVerifiedOperatorWithoutSecrets() {
        var old = credentials.create("rotation-service", ACTOR);
        var fresh = credentials.rotate(old.credential().id(), ACTOR);
        assertThat(get(old.token()).getStatusCode().value()).isEqualTo(401);
        assertThat(get(fresh.token()).getStatusCode().value()).isEqualTo(200);
        credentials.disable(fresh.credential().id(), ACTOR);
        var events = outbox.payloadsOf("AuditEvent").stream()
                .map(value -> JsonMapper.builder().build().readValue(value, AuditEvent.class))
                .filter(event -> "rotation-service".equals(event.detail().get("service")))
                .filter(event -> !"serviceCredential.authenticate".equals(event.action()))
                .toList();
        assertThat(events)
                .extracting(AuditEvent::action)
                .containsExactly("serviceCredential.create", "serviceCredential.rotate", "serviceCredential.disable");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(ACTOR);
            assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
            assertThat(event.tenantId()).isNull();
            assertThat(event.detail().get("fingerprint").toString()).hasSize(8);
            assertThat(event.toString()).doesNotContain(old.token(), fresh.token());
        });
        assertThat(fresh.toString()).doesNotContain(fresh.token());
    }

    @Test
    void repeatedUnknownTokensFromOneSourceProduceOneFailureAudit() {
        for (int i = 0; i < 25; i++) {
            assertThat(get("unknown-token-" + i).getStatusCode().value()).isEqualTo(401);
        }
        var events = outbox.payloadsOf("AuditEvent").stream()
                .map(value -> JsonMapper.builder().build().readValue(value, AuditEvent.class))
                .filter(event -> "serviceCredential.authenticate".equals(event.action()))
                .filter(event -> "unknown".equals(event.detail().get("service")))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).detail().get("source")).isEqualTo("127.0.0.1");
        assertThat(events.get(0).toString()).doesNotContain("unknown-token-");
    }

    @Test
    void staticBootstrapStillWorksAndWarnsWithoutLoggingTheToken() {
        var logger = (Logger) LoggerFactory.getLogger(ServiceCredentialAuthentication.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(get("legacy-service-token").getStatusCode().value()).isEqualTo(200);
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel().toString()).isEqualTo("WARN");
                assertThat(event.getFormattedMessage()).contains("静态凭证已弃用", "legacy-service");
            });
            assertThat(appender.list)
                    .allSatisfy(
                            event -> assertThat(event.getFormattedMessage()).doesNotContain("legacy-service-token"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @Sql("/fixtures/service_credentials.sql")
    void databaseIdentityWinsAndDisablingCannotReviveTheSameStaticToken() {
        var response = RestClient.create("http://127.0.0.1:" + port)
                .post()
                .uri("/internal/manifests")
                .headers(headers -> headers.setBearerAuth("shadow-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "service", "registered-shadow", "version", "1", "permissions", List.of(), "menus", List.of()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, result) -> {})
                .toEntity(Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        credentials.disable(UUID.fromString("019a0110-0000-7000-8000-000000000901"), ACTOR);
        assertThat(get("shadow-token").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void databaseFailureReturns503EvenForAValidStaticTokenWhileMalformedHeadersRemain401() throws Exception {
        DataSource pool = dataSource.unwrap(DataSource.class);
        doThrow(new SQLException("database unavailable")).when(pool).getConnection();
        try {
            var response = get("legacy-service-token");
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).containsEntry("code", "service-credential.unavailable");
            assertThat(response.getBody().get("errorMessage")).isEqualTo("Internal error");
            for (String invalid : new String[] {null, "", "token with space"}) {
                assertThat(get(invalid).getStatusCode().value()).isEqualTo(401);
            }
        } finally {
            reset(pool);
        }
    }

    ResponseEntity<Map> get(String token) {
        return RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/internal/services")
                .headers(headers -> {
                    if (token != null) headers.setBearerAuth(token);
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(Map.class);
    }
}
