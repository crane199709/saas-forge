package io.saasforge.sdk.mq;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 同一 JVM 共享 PostgreSQL，各测试类独立建库，使用非超级用户验证 RLS。 */
final class SharedContainers {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE mq_app LOGIN PASSWORD 'mq_test'");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static Database database() {
        String name = "mq_test_" + DATABASES.incrementAndGet();
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER mq_app");
            return new Database(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    record Database(String getJdbcUrl) {
        String getUsername() {
            return "mq_app";
        }

        String getPassword() {
            return "mq_test";
        }
    }
}
