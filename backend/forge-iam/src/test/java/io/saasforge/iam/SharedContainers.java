package io.saasforge.iam;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 同一测试 JVM 内共享的 PostgreSQL 与 Redis 容器。
 *
 * <p>各测试类原先各自启动两个容器，容器启动占了测试总时长的近四分之一。测试类的 Spring 配置各不相同、上下文无法复用，
 * 因此只共享容器、不共享数据：每个测试类分到一个新建数据库（重新执行部署用的初始化脚本）与一个清空的 Redis 库，
 * 与原先「每个类一套空容器」的隔离等价。容器在 JVM 退出后由 Testcontainers 回收。
 */
final class SharedContainers {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final AtomicInteger REDIS_DATABASES = new AtomicInteger();
    // redis 默认 16 个库；每个 fork 的测试类少于此数，超出时循环复用并先清空
    private static final int REDIS_DATABASE_COUNT = 16;

    private SharedContainers() {}

    /** 为调用的测试类新建一个已执行初始化脚本的数据库。 */
    static Postgres postgres() {
        String name = "forge_" + DATABASES.incrementAndGet();
        exec(PostgresHolder.CONTAINER, "psql", "-U", "test", "-d", "forge", "-c", "CREATE DATABASE " + name);
        exec(
                PostgresHolder.CONTAINER,
                "psql",
                "-U",
                "test",
                "-d",
                name,
                "-v",
                "ON_ERROR_STOP=1",
                "-f",
                "/docker-entrypoint-initdb.d/01-service-schemas.sql");
        return new Postgres(PostgresHolder.CONTAINER.getJdbcUrl().replace("/forge?", "/" + name + "?"));
    }

    /** 为调用的测试类分配一个清空的 Redis 库。 */
    static Redis redis() {
        int database = REDIS_DATABASES.getAndIncrement() % REDIS_DATABASE_COUNT;
        exec(RedisHolder.CONTAINER, "redis-cli", "-n", String.valueOf(database), "FLUSHDB");
        return new Redis(RedisHolder.CONTAINER, database);
    }

    private static void exec(GenericContainer<?> container, String... command) {
        try {
            ExecResult result = container.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + result.getStderr());
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    // 访问方法与 Testcontainers 容器同名，测试类原有的属性注册与连接代码无需改动
    record Postgres(String jdbcUrl) {
        String getJdbcUrl() {
            return jdbcUrl;
        }
    }

    record Redis(GenericContainer<?> container, int database) {
        String getHost() {
            return container.getHost();
        }

        Integer getMappedPort(int port) {
            return container.getMappedPort(port);
        }
    }

    // 持有类按需启动：只用 PostgreSQL 的测试 JVM 不必启动 Redis
    private static final class PostgresHolder {
        static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("forge")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

        static {
            CONTAINER.start();
        }
    }

    private static final class RedisHolder {
        static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

        static {
            CONTAINER.start();
        }
    }
}
