package io.saasforge.sdk.security;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * 同一测试 JVM 内共享的 Redis 容器：各测试类不再各自启动容器，每个类分到一个清空的 Redis 库，隔离与原先等价。
 * 容器在 JVM 退出后由 Testcontainers 回收。
 */
final class SharedContainers {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final AtomicInteger REDIS_DATABASES = new AtomicInteger();
    // redis 默认 16 个库；本模块测试类少于此数，超出时循环复用并先清空
    private static final int REDIS_DATABASE_COUNT = 16;

    static {
        REDIS.start();
    }

    private SharedContainers() {}

    /** 为调用的测试类分配一个清空的 Redis 库。 */
    static Redis redis() {
        int database = REDIS_DATABASES.getAndIncrement() % REDIS_DATABASE_COUNT;
        try {
            ExecResult result = REDIS.execInContainer("redis-cli", "-n", String.valueOf(database), "FLUSHDB");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("FLUSHDB failed: " + result.getStderr());
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
        return new Redis(database);
    }

    // 访问方法与 Testcontainers 容器同名，测试类原有的属性注册代码无需改动
    record Redis(int database) {
        String getHost() {
            return REDIS.getHost();
        }

        Integer getMappedPort(int port) {
            return REDIS.getMappedPort(port);
        }
    }
}
