package io.saasforge.sdk.io;

import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.provisioning.TenantCleanupAutoConfiguration;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.security.FieldMaskingPolicyProvider;
import io.saasforge.sdk.storage.ObjectStorage;
import jakarta.validation.Validator;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 引入 starter 后提供任务、Excel 与定时清理；服务复制 schema.sql 到自己的 Flyway 迁移。 */
@AutoConfiguration(
        before = TenantCleanupAutoConfiguration.class,
        afterName = {
            "StorageAutoConfiguration",
            "DataPermissionAutoConfiguration",
            "AuditAutoConfiguration",
            "ReferenceDataAutoConfiguration",
            "io.saasforge.sdk.security.FieldMaskingAutoConfiguration"
        })
@EnableConfigurationProperties(IoProperties.class)
public final class IoAutoConfiguration {
    @Bean
    IoTaskMapper ioTaskMapper(SqlSessionTemplate session) {
        var config = session.getConfiguration();
        if (!config.hasMapper(IoTaskMapper.class)) {
            config.addMapper(IoTaskMapper.class);
        }
        return session.getMapper(IoTaskMapper.class);
    }

    @Bean
    ImportBackupMapper importBackupMapper(SqlSessionTemplate session) {
        var config = session.getConfiguration();
        if (!config.hasMapper(ImportBackupMapper.class)) {
            config.addMapper(ImportBackupMapper.class);
        }
        return session.getMapper(ImportBackupMapper.class);
    }

    @Bean
    ImportBackups importBackups(ImportBackupMapper mapper) {
        return new ImportBackups(mapper);
    }

    @Bean
    TenantDataCleaner importBackupCleaner(ImportBackupMapper mapper) {
        return event -> {
            mapper.markTenantDeleted();
            mapper.clearTaskSnapshots();
        };
    }

    @Bean
    IoTasks ioTasks(
            IoTaskMapper mapper,
            ObjectStorage storage,
            DataPermission permission,
            AuditRecorder audit,
            MessageSource messages,
            StringRedisTemplate redis,
            Environment environment,
            PlatformTransactionManager manager,
            IoProperties properties) {
        return new IoTasks(
                mapper,
                storage,
                permission,
                audit,
                messages,
                redis,
                environment.getRequiredProperty("spring.application.name"),
                independent(manager),
                properties);
    }

    @Bean
    IoMaintenance ioMaintenance(
            AuditRecorder audit,
            IoTaskMapper mapper,
            ObjectStorage storage,
            IoProperties properties,
            StringRedisTemplate redis,
            IoTasks tasks,
            PlatformTransactionManager manager) {
        return new IoMaintenance(audit, mapper, storage, properties, redis, tasks, new TransactionTemplate(manager));
    }

    @Bean
    IoTaskController ioTaskController(IoTasks tasks) {
        return new IoTaskController(tasks);
    }

    @Bean
    ExcelIo excelIo(
            IoTasks tasks,
            ObjectStorage storage,
            ReferenceData reference,
            Validator validator,
            IoProperties properties,
            PlatformTransactionManager manager,
            ObjectProvider<FieldMaskingPolicyProvider> masking,
            ImportBackups backups) {
        return new ExcelIo(
                tasks,
                storage,
                reference,
                validator,
                properties,
                new TransactionTemplate(manager),
                masking.getIfAvailable(),
                backups);
    }

    // REQUIRES_NEW：任务行须在交给线程池前提交。工作线程用另一条连接执行 start，读不到调用方事务中未提交的行，任务会被直接跳过。
    private static TransactionTemplate independent(PlatformTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
