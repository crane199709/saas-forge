package io.saasforge.sdk.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.saasforge.sdk.core.BusinessZoneProperties;
import io.saasforge.sdk.tenant.TenantStatusLookup;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.util.ReflectionUtils;

final class TaskRegistrar implements BeanPostProcessor, SmartInitializingSingleton, DisposableBean {
    private final ConfigurableListableBeanFactory beans;
    private final Environment environment;
    private final ObjectProvider<SchedulingProperties> properties;
    private final List<Task> tasks = new ArrayList<>();
    private final List<ScheduledFuture<?>> schedules = new ArrayList<>();
    private TaskRunner runner;

    TaskRegistrar(
            ConfigurableListableBeanFactory beans,
            Environment environment,
            ObjectProvider<SchedulingProperties> properties) {
        this.beans = beans;
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        MethodIntrospector.selectMethods(AopProxyUtils.ultimateTargetClass(bean), (MethodIntrospector.MetadataLookup<
                                Task>)
                        method -> {
                            GlobalTask global = AnnotatedElementUtils.findMergedAnnotation(method, GlobalTask.class);
                            TenantTask tenant = AnnotatedElementUtils.findMergedAnnotation(method, TenantTask.class);
                            if (global == null && tenant == null) return null;
                            if (global != null && tenant != null) {
                                throw new IllegalStateException(
                                        "Task must declare only one task annotation: " + method);
                            }
                            Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
                            return global != null
                                    ? new Task(
                                            bean, invocable, global.name(), global.cron(), global.fixedDelay(), false)
                                    : new Task(
                                            bean, invocable, tenant.name(), tenant.cron(), tenant.fixedDelay(), true);
                        })
                .forEach((method, task) -> tasks.add(task));
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var values = new EmbeddedValueResolver(beans);
        Set<String> names = new HashSet<>();
        List<Registration> registrations = new ArrayList<>();
        for (Task task : tasks) {
            String name = values.resolveStringValue(task.name());
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Task name must not be blank");
            }
            if (!names.add(name)) {
                throw new IllegalStateException("Task name is duplicate: " + name);
            }
            if (task.method().getParameterCount() != 0 || task.method().getReturnType() != void.class) {
                throw new IllegalStateException("Task must be a no-argument void method: " + task.method());
            }
            String cron = values.resolveStringValue(task.cron());
            String delay = values.resolveStringValue(task.fixedDelay());
            if (cron.isBlank() == delay.isBlank()) {
                throw new IllegalStateException("Task must declare exactly one of cron and fixedDelay: " + name);
            }
            if (!cron.isBlank()) {
                registrations.add(new Registration(
                        task,
                        name,
                        null,
                        new CronTrigger(
                                cron,
                                beans.getBean(BusinessZoneProperties.class).require("cron task"))));
            } else {
                Duration interval = DurationStyle.detectAndParse(delay);
                if (interval.isNegative() || interval.isZero()) {
                    throw new IllegalStateException("Task fixedDelay must be positive: " + name);
                }
                registrations.add(new Registration(task, name, interval, null));
            }
        }
        if (registrations.isEmpty()) {
            return;
        }
        MeterRegistry metrics = beans.getBeanProvider(MeterRegistry.class).getIfAvailable(SimpleMeterRegistry::new);
        TenantTaskExecutor tenantExecutor = tasks.stream().anyMatch(Task::tenant)
                ? new TenantTaskExecutor(
                        new TenantDirectoryClient(properties.getObject()),
                        beans.getBean(TenantStatusLookup.class),
                        metrics)
                : null;
        runner = new TaskRunner(
                new RedisTaskLock(
                        beans.getBean(StringRedisTemplate.class),
                        environment.getRequiredProperty("spring.application.name")),
                metrics,
                beans.getBean(TaskRunHistory.class));
        TaskScheduler scheduler = beans.getBean("forgeTaskScheduler", TaskScheduler.class);
        for (Registration registration : registrations) {
            Task task = registration.task();
            ReflectionUtils.makeAccessible(task.method());
            Runnable method = () -> ReflectionUtils.invokeMethod(task.method(), task.bean());
            Consumer<TaskRunOutcome> body = task.tenant()
                    ? outcome -> tenantExecutor.execute(registration.name(), method, outcome)
                    : outcome -> method.run();
            Runnable invocation = () -> runner.trigger(
                    registration.name(), task.tenant() ? TaskRun.Type.TENANT : TaskRun.Type.GLOBAL, body);
            if (registration.cron() != null) {
                schedules.add(scheduler.schedule(invocation, registration.cron()));
            } else {
                schedules.add(scheduler.scheduleWithFixedDelay(invocation, registration.delay()));
            }
        }
    }

    @Override
    public void destroy() {
        schedules.stream().filter(future -> future != null).forEach(future -> future.cancel(false));
        if (runner != null) {
            runner.close();
        }
    }

    private record Registration(Task task, String name, Duration delay, CronTrigger cron) {}

    private record Task(Object bean, Method method, String name, String cron, String fixedDelay, boolean tenant) {}
}
