package io.saasforge.sdk.audit;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.mq.MqAutoConfiguration;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 业务服务引入 starter 即可使用 {@link AuditLog} 与 {@link AuditRecorder}；审计事件经 forge-sdk-mq 的 Outbox 投递。 */
@AutoConfiguration(after = MqAutoConfiguration.class)
public final class AuditAutoConfiguration {
    /**
     * 拦截器的顺序：小于方法安全（{@code @RequiresPermission} 为 200）与事务（最低优先级），审计包在两者之外，
     * 权限不足与事务提交失败都能记为失败。
     */
    static final int ORDER = 0;

    /**
     * 审计记录入口。服务名取 {@code spring.application.name}，未配置时启动失败，避免无法区分事件来源。
     *
     * @param publisher Outbox 事件发布
     * @param transactionManager 用于在独立事务中写入 Outbox
     * @param tracer 用于填入 traceId，未启用 tracing 时为空
     * @param environment 读取服务名
     * @return 记录器
     */
    @Bean
    public AuditRecorder auditRecorder(
            EventPublisher publisher,
            PlatformTransactionManager transactionManager,
            ObjectProvider<Tracer> tracer,
            Environment environment) {
        var independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new AuditRecorder(
                publisher, independent, environment.getRequiredProperty("spring.application.name"), tracer);
    }

    /**
     * 拦截标注 {@link AuditLog} 的方法。
     *
     * <p>静态方法且延迟取得依赖：Advisor 在其他 Bean 之前创建，不能提前初始化数据源等组件。Spring Boot 默认的 AOP 自动代理只应用
     * 基础设施角色的 Advisor，因此以 {@code ROLE_INFRASTRUCTURE} 声明。
     *
     * @param recorder 审计记录器
     * @param actors 当前操作人，未引入 forge-sdk-security 时为空，事件不带操作人
     * @return Advisor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor forgeAuditLogAdvisor(
            ObjectProvider<AuditRecorder> recorder, ObjectProvider<CurrentActorProvider> actors) {
        var advisor = new DefaultPointcutAdvisor(
                new AnnotationMatchingPointcut(null, AuditLog.class, true), new AuditLogInterceptor(recorder, actors));
        advisor.setOrder(ORDER);
        return advisor;
    }
}
