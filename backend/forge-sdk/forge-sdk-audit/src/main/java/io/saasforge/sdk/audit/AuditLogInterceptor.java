package io.saasforge.sdk.audit;

import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** 执行 {@link AuditLog} 标注的方法并按结果记录 Audit Event。 */
final class AuditLogInterceptor implements MethodInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuditLogInterceptor.class);
    // 按类名判断，审计模块不为一个异常类型引入 Spring Security
    private static final String ACCESS_DENIED = "org.springframework.security.access.AccessDeniedException";

    private final ObjectProvider<AuditRecorder> recorder;
    private final ObjectProvider<CurrentActorProvider> actors;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();

    AuditLogInterceptor(ObjectProvider<AuditRecorder> recorder, ObjectProvider<CurrentActorProvider> actors) {
        this.recorder = recorder;
        this.actors = actors;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getThis() == null
                ? invocation.getMethod()
                : AopUtils.getMostSpecificMethod(invocation.getMethod(), AopUtils.getTargetClass(invocation.getThis()));
        AuditLog audit = AnnotatedElementUtils.findMergedAnnotation(method, AuditLog.class);
        if (audit == null) {
            return invocation.proceed();
        }
        // 在调用前取得上下文：方法内部可能切换租户或认证
        UUID tenantId = CurrentTenant.find().orElse(null);
        UUID actorId = actors.stream()
                .findFirst()
                .flatMap(CurrentActorProvider::currentActor)
                .orElse(null);
        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable error) {
            AuditEvent event = event(audit, tenantId, actorId, method, invocation.getArguments(), null)
                    .failure(errorCode(error))
                    .build();
            recorder.getObject().recordNow(event);
            throw error;
        }
        recorder.getObject()
                .record(event(audit, tenantId, actorId, method, invocation.getArguments(), result)
                        .build());
        return result;
    }

    private AuditEvent.Builder event(
            AuditLog audit, UUID tenantId, UUID actorId, Method method, Object[] arguments, Object result) {
        AuditRealm realm = tenantId == null ? AuditRealm.PLATFORM : AuditRealm.TENANT;
        return AuditEvent.builder(realm, AuditCategory.OPERATION, audit.action())
                .tenant(tenantId)
                .actor(actorId)
                .resource(
                        audit.resourceType().isEmpty() ? null : audit.resourceType(),
                        resourceId(audit, method, arguments, result));
    }

    // 对象标识只是辅助信息，表达式出错不能让业务调用失败
    private String resourceId(AuditLog audit, Method method, Object[] arguments, Object result) {
        if (audit.resourceId().isEmpty()) {
            return null;
        }
        try {
            var context = new MethodBasedEvaluationContext(null, method, arguments, parameterNames);
            context.setVariable("result", result);
            Object value = expressions
                    .computeIfAbsent(audit.resourceId(), parser::parseExpression)
                    .getValue(context);
            return value == null ? null : value.toString();
        } catch (RuntimeException e) {
            // result 为 null（调用失败或方法无返回值）时，引用 #result 的表达式出错属预期，不告警。
            if (result != null) {
                log.warn("Failed to evaluate audit resourceId '{}' on {}", audit.resourceId(), method, e);
            }
            return null;
        }
    }

    private static String errorCode(Throwable error) {
        if (error instanceof ForgeException forge) {
            return forge.errorCode().code();
        }
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            if (ACCESS_DENIED.equals(type.getName())) {
                return FrameworkErrorCode.AUTH_FORBIDDEN.code();
            }
        }
        return FrameworkErrorCode.SYSTEM_ERROR.code();
    }
}
