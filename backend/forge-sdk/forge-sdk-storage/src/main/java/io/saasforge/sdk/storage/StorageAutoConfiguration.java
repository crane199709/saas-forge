package io.saasforge.sdk.storage;

import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.mq.MqAutoConfiguration;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.time.Clock;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 业务服务引入 starter 并配置 {@code forge.storage} 即可注入 {@link ObjectStorage}。
 *
 * <p>服务须在自己的 Flyway 迁移中创建 {@code forge_storage_object} 与 {@code forge_storage_quota}，建表语句见
 * {@code io/saasforge/sdk/storage/schema.sql}。SDK 订阅 {@link TenantPlanChanged} 维护存储容量 Quota，服务不能再声明同一事件的
 * {@link EventHandler}（同一消费组内一个事件只分派给一个处理器）。
 *
 * <p>排在 {@link MqAutoConfiguration} 之前：消费端只在存在 {@link EventHandler} Bean 时创建。
 */
@AutoConfiguration(before = MqAutoConfiguration.class)
@EnableConfigurationProperties(StorageProperties.class)
public final class StorageAutoConfiguration {
    @Bean(destroyMethod = "close")
    S3Client forgeS3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .forcePathStyle(properties.pathStyle())
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner forgeS3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build())
                .build();
    }

    /**
     * 对象存储入口。服务名取 {@code spring.application.name} 作为对象 key 的第二段，未配置时启动失败。
     *
     * @param sqlSession 参与 Spring 事务的 SqlSession
     * @param transactionManager 用于 Quota 校验与对象登记的事务；调用方已在事务中时加入调用方的事务
     * @param clocks 可选时钟；未声明时使用 UTC 系统时钟
     */
    @Bean
    public ObjectStorage objectStorage(
            S3Client forgeS3Client,
            S3Presigner forgeS3Presigner,
            SqlSessionTemplate sqlSession,
            PlatformTransactionManager transactionManager,
            StorageProperties properties,
            Environment environment,
            ObjectProvider<Clock> clocks) {
        return new ObjectStorage(
                forgeS3Client,
                forgeS3Presigner,
                mapper(sqlSession),
                new TransactionTemplate(transactionManager),
                properties,
                environment.getRequiredProperty("spring.application.name"),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    EventHandler<TenantPlanChanged> storageQuotaHandler(SqlSessionTemplate sqlSession) {
        return new StorageQuotaHandler(mapper(sqlSession));
    }

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }

    /** 在 SqlSessionFactory 建好之后注册 Mapper，理由见 {@code MqAutoConfiguration#mapper}。 */
    private static StorageObjectMapper mapper(SqlSessionTemplate sqlSession) {
        var configuration = sqlSession.getConfiguration();
        if (!configuration.hasMapper(StorageObjectMapper.class)) {
            configuration.addMapper(StorageObjectMapper.class);
        }
        return sqlSession.getMapper(StorageObjectMapper.class);
    }
}
