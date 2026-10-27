package io.saasforge.sdk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.core.MembershipStateKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MembershipGrantStoreTest {
    static final UUID IDENTITY_ID = UUID.fromString("019a0110-0000-7000-8000-000000000002");
    static final UUID TENANT_ID = UUID.fromString("019a0110-0000-7000-8000-000000000001");
    static final UUID MEMBERSHIP_ID = UUID.fromString("019a0110-0000-7000-8000-000000000003");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    void readsGrantsOfTheTokenVersionAndCachesThemUntilTheVersionChanges() {
        publish(1, "{\"roles\":[\"tenant-admin\"],\"permissions\":[\"order:read\"]}");
        var store = new MembershipGrantStore(template);

        var first = store.grants(context(1));
        assertThat(first.roles()).containsExactly("tenant-admin");
        assertThat(first.permissions()).containsExactly("order:read");

        // 同一版本命中本地缓存：Redis 中的明细被删除也不影响读取。
        template.delete(MembershipStateKeys.grants(TENANT_ID, MEMBERSHIP_ID, 1));
        assertThat(store.grants(context(1))).isEqualTo(first);

        // 版本变化后缓存失效，读取新版本的明细。
        publish(2, "{\"roles\":[],\"permissions\":[\"order:read\",\"order:approve\"]}");
        assertThat(store.grants(context(2)).permissions()).isEqualTo(Set.of("order:read", "order:approve"));
    }

    @Test
    void sameVersionIsReReadAfterTheRefreshInterval() {
        // iam 在业务服务上报新清单后以同一版本覆盖明细，缓存过期后才能读到新增的权限码。
        publish(5, "{\"roles\":[],\"permissions\":[\"order:read\"]}");
        var now = new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));
        var clock = new Clock() {
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
                return now.get();
            }
        };
        var store = new MembershipGrantStore(template, clock);
        assertThat(store.grants(context(5)).permissions()).containsExactly("order:read");

        publish(5, "{\"roles\":[],\"permissions\":[\"order:read\",\"order:export\"]}");
        assertThat(store.grants(context(5)).permissions()).containsExactly("order:read");

        now.set(now.get().plus(MembershipGrantStore.REFRESH_AFTER));
        assertThat(store.grants(context(5)).permissions()).contains("order:export");
    }

    @Test
    void unpublishedGrantsFailInsteadOfGrantingNothingSilently() {
        var store = new MembershipGrantStore(template);
        assertThatThrownBy(() -> store.grants(context(99))).isInstanceOf(IllegalStateException.class);
    }

    private static void publish(long version, String json) {
        template.opsForValue().set(MembershipStateKeys.grants(TENANT_ID, MEMBERSHIP_ID, version), json);
    }

    private static TenantContext context(long version) {
        return new TenantContext(IDENTITY_ID, TENANT_ID, MEMBERSHIP_ID, version);
    }
}
