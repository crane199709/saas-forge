package io.saasforge.sdk.referencedata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.ReferenceDataKeys;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import jakarta.validation.Validator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 业务服务经 SDK 读取当前租户的生效字典与参数：按租户缓存、按 Redis 版本号失效、过期兜底与取值校验（参考数据工单 03）。 */
@Testcontainers
class ReferenceDataTest {
    static final UUID TENANT = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("019a0110-0000-7000-8000-0000000000bb");
    static final String TOKEN = "demo-token";

    /** 与 iam 共用的 Redis。 */
    @Container
    static final GenericContainer<?> redisServer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    final Map<UUID, String> responses = new ConcurrentHashMap<>();
    final Map<UUID, AtomicInteger> requests = new ConcurrentHashMap<>();
    final MutableClock clock = new MutableClock();
    HttpServer iam;

    @BeforeEach
    void startIam() throws Exception {
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/reference-data/", exchange -> {
            UUID tenant =
                    UUID.fromString(exchange.getRequestURI().getPath().substring("/internal/reference-data/".length()));
            boolean authorized =
                    ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.computeIfAbsent(tenant, key -> new AtomicInteger()).incrementAndGet();
            byte[] body = (authorized ? responses.getOrDefault(tenant, data("enabled", 50)) : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        iam.start();
    }

    @AfterEach
    void stopIam() {
        iam.stop(0);
        redis.delete(List.of(ReferenceDataKeys.version(TENANT), ReferenceDataKeys.version(OTHER)));
    }

    @BeforeAll
    static void connectRedis() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnectRedis() {
        factory.destroy();
    }

    @Test
    void readsTheCurrentTenantsDictionariesAndParametersByDeclaredType() {
        responses.put(TENANT, data("disabled", 80));
        runner().run(context -> CurrentTenant.run(TENANT, () -> {
            ReferenceData referenceData = context.getBean(ReferenceData.class);

            assertThat(referenceData.items("customer-source"))
                    .extracting(TenantDictionaryItem::code)
                    .containsExactly("online", "referral", "custom.fair");
            assertThat(referenceData.selectableItems("customer-source"))
                    .extracting(TenantDictionaryItem::code)
                    .containsExactly("online", "custom.fair");
            assertThat(referenceData.isSelectable("customer-source", "custom.fair"))
                    .isTrue();
            assertThat(referenceData.isSelectable("customer-source", "referral"))
                    .isFalse();
            assertThat(referenceData.isSelectable("customer-source", "unknown")).isFalse();
            // 「服务:编码」引用其他服务的字典，带本服务前缀等同于不带前缀。
            assertThat(referenceData.selectableItems("crm-service:industry"))
                    .extracting(TenantDictionaryItem::name)
                    .containsExactly("制造业");
            assertThat(referenceData.items("demo-service:customer-source")).hasSize(3);

            assertThat(referenceData.durationParameter("order.auto-close")).isEqualTo(Duration.ofHours(1));
            assertThat(referenceData.integerParameter("order.max-items")).isEqualTo(80L);
            assertThat(referenceData.decimalParameter("order.discount")).isEqualByComparingTo("0.95");
            assertThat(referenceData.booleanParameter("order.notify")).isTrue();
            assertThat(referenceData.stringParameter("order.remark")).isEqualTo("请尽快付款");
            assertThat(referenceData.dictionaryParameter("customer.default-source"))
                    .isEqualTo("online");
            assertThatThrownBy(() -> referenceData.integerParameter("order.auto-close"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duration");
            assertThatThrownBy(() -> referenceData.items("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("demo-service:missing");
        }));
    }

    @Test
    void onceIamAdvancesTheTenantsVersionTheNewValuesAreRead() {
        responses.put(TENANT, data("enabled", 50));
        redis.opsForValue().set(ReferenceDataKeys.version(TENANT), "3");
        runner().run(context -> {
            ReferenceData referenceData = context.getBean(ReferenceData.class);
            CurrentTenant.run(
                    TENANT,
                    () -> assertThat(referenceData.integerParameter("order.max-items"))
                            .isEqualTo(50L));
            CurrentTenant.run(OTHER, () -> referenceData.integerParameter("order.max-items"));
            responses.put(TENANT, data("disabled", 80));

            // 版本未变时缓存期内不再请求 iam。
            CurrentTenant.run(TENANT, () -> {
                assertThat(referenceData.integerParameter("order.max-items")).isEqualTo(50L);
                assertThat(referenceData.isSelectable("customer-source", "referral"))
                        .isTrue();
            });
            assertThat(requests.get(TENANT)).hasValue(1);

            // iam 提交修改后递增版本号，不发任何事件；缓存远未过期。
            redis.opsForValue().increment(ReferenceDataKeys.version(TENANT));

            CurrentTenant.run(TENANT, () -> {
                assertThat(referenceData.integerParameter("order.max-items")).isEqualTo(80L);
                assertThat(referenceData.isSelectable("customer-source", "referral"))
                        .isFalse();
            });
            assertThat(requests.get(TENANT)).hasValue(2);
            // 其他租户的版本未变，仍用缓存。
            CurrentTenant.run(OTHER, () -> referenceData.integerParameter("order.max-items"));
            assertThat(requests.get(OTHER)).hasValue(1);
        });
    }

    @Test
    void withoutAVersionInRedisNewValuesAreReadOnceTheCacheExpires() {
        // 租户从未修改过，或 iam 提交后递增失败：Redis 中没有版本号，只能依靠过期。
        responses.put(TENANT, data("enabled", 50));
        runner().withPropertyValues("forge.reference-data.cache-ttl=10m")
                .run(context -> CurrentTenant.run(TENANT, () -> {
                    ReferenceData referenceData = context.getBean(ReferenceData.class);
                    assertThat(referenceData.integerParameter("order.max-items"))
                            .isEqualTo(50L);
                    responses.put(TENANT, data("enabled", 80));

                    clock.advance(Duration.ofMinutes(9));
                    assertThat(referenceData.integerParameter("order.max-items"))
                            .isEqualTo(50L);
                    clock.advance(Duration.ofMinutes(1));
                    assertThat(referenceData.integerParameter("order.max-items"))
                            .isEqualTo(80L);
                }));
    }

    @Test
    void dictionaryValueAcceptsOnlySelectableItemsOfTheCurrentTenant() {
        responses.put(TENANT, data("disabled", 50));
        runner().run(context -> CurrentTenant.run(TENANT, () -> {
            Validator validator = context.getBean(Validator.class);

            assertThat(validator.validate(new Customer("online"))).isEmpty();
            assertThat(validator.validate(new Customer("custom.fair"))).isEmpty();
            // 未填写由 @NotNull 等约束决定，@DictionaryValue 只校验填写的编码。
            assertThat(validator.validate(new Customer(null))).isEmpty();
            assertThat(validator.validate(new Customer("referral")))
                    .singleElement()
                    .satisfies(violation -> {
                        assertThat(violation.getPropertyPath()).hasToString("source");
                        assertThat(violation.getConstraintDescriptor().getAnnotation())
                                .isInstanceOf(DictionaryValue.class);
                    });
            assertThat(validator.validate(new Customer("unknown"))).hasSize(1);
        }));
    }

    @Test
    void failingToReachIamIsReportedAsServiceUnavailable() {
        runner().withPropertyValues("forge.reference-data.token=wrong")
                .run(context -> CurrentTenant.run(TENANT, () -> {
                    ReferenceData referenceData = context.getBean(ReferenceData.class);
                    assertThatThrownBy(() -> referenceData.items("customer-source"))
                            .isInstanceOfSatisfying(
                                    ForgeException.class,
                                    error -> assertThat(error.errorCode())
                                            .isEqualTo(FrameworkErrorCode.SYSTEM_UNAVAILABLE));
                }));
    }

    @Test
    void serviceFailsToStartWithoutTheIamAddress() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ReferenceDataAutoConfiguration.class))
                .withPropertyValues("spring.application.name=demo-service", "forge.reference-data.token=" + TOKEN)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("forge.reference-data.iam-uri"));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ReferenceDataAutoConfiguration.class, ValidationAutoConfiguration.class))
                .withBean(Clock.class, () -> clock)
                .withBean(StringRedisTemplate.class, () -> redis)
                .withPropertyValues(
                        "spring.application.name=demo-service",
                        "forge.reference-data.iam-uri=http://127.0.0.1:"
                                + iam.getAddress().getPort(),
                        "forge.reference-data.token=" + TOKEN);
    }

    /** iam 内部接口的响应：demo-service 的客户来源字典（{@code referral} 的状态可变）与各类型参数，另含 crm-service 的字典。 */
    private static String data(String referralStatus, int maxItems) {
        return """
                {"code":"ok","httpStatus":200,"data":{"dictionaries":[
                  {"service":"demo-service","code":"customer-source","name":"客户来源","extensible":true,"items":[
                    {"code":"online","name":"线上","sort":1,"custom":false,"status":"enabled"},
                    {"code":"referral","name":"转介绍","sort":2,"custom":false,"status":"%s"},
                    {"code":"custom.fair","name":"展会","sort":3,"custom":true,"status":"enabled"}]},
                  {"service":"crm-service","code":"industry","name":"行业","extensible":false,"items":[
                    {"code":"manufacturing","name":"制造业","sort":1,"custom":false,"status":"enabled"}]}],
                 "parameters":[
                  {"service":"demo-service","code":"order.auto-close","type":"duration","value":"PT1H"},
                  {"service":"demo-service","code":"order.max-items","type":"integer","value":%d},
                  {"service":"demo-service","code":"order.discount","type":"decimal","value":0.95},
                  {"service":"demo-service","code":"order.notify","type":"boolean","value":true},
                  {"service":"demo-service","code":"order.remark","type":"string","value":"请尽快付款"},
                  {"service":"demo-service","code":"customer.default-source","type":"dictionary",
                   "dictionary":"customer-source","value":"online"}]}}
                """.formatted(referralStatus, maxItems);
    }

    record Customer(@DictionaryValue("customer-source") String source) {}

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-04T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
