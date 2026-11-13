package io.saasforge.sdk.scheduling;

import io.saasforge.sdk.core.api.ForgeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 单次执行线程内累计结果，失败明细有界，计数不受明细上限影响。 */
final class TaskRunOutcome {
    private int successes;
    private int failures;
    private final List<TaskRun.FailedTenant> failedTenants = new ArrayList<>();
    private String errorSummary;
    private boolean failed;

    void succeeded() {
        successes++;
    }

    void tenantFailed(UUID tenant, Throwable error) {
        failures++;
        String summary = summary(error);
        if (failedTenants.size() < 100) {
            failedTenants.add(new TaskRun.FailedTenant(tenant, summary));
        }
        if (errorSummary == null) {
            errorSummary = summary;
        }
    }

    void failed(Throwable error) {
        failed = true;
        errorSummary = summary(error);
    }

    TaskRun complete(TaskRun run) {
        TaskRun.Status status = failed || failures > 0 && successes == 0
                ? TaskRun.Status.FAILED
                : failures > 0 ? TaskRun.Status.PARTIALLY_FAILED : TaskRun.Status.SUCCEEDED;
        return new TaskRun(
                run.id(),
                run.name(),
                run.type(),
                run.trigger(),
                run.operatorId(),
                run.targetTenantId(),
                status,
                run.startedAt(),
                Instant.now(),
                successes,
                failures,
                failedTenants,
                errorSummary);
    }

    private static String summary(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof UndeclaredThrowableException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        // message 和 ForgeException 的参数都可能含业务数据，不能进入 Redis。
        return error.getClass().getName()
                + (error instanceof ForgeException forge
                        ? ": " + forge.errorCode().code()
                        : "");
    }
}
