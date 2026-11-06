package io.saasforge.sdk.scheduling;

import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.GenericContainer;

/** 同一测试 JVM 共享 Redis，每个测试类独立使用一个库。 */
final class SharedContainers {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static {
        REDIS.start();
    }

    private SharedContainers() {}

    static Redis redis() {
        int database = DATABASES.getAndIncrement();
        if (database >= 16) throw new IllegalStateException("Redis test databases exhausted");
        return new Redis(database);
    }

    record Redis(int database) {
        String getHost() {
            return REDIS.getHost();
        }

        int getMappedPort(int port) {
            return REDIS.getMappedPort(port);
        }
    }
}
