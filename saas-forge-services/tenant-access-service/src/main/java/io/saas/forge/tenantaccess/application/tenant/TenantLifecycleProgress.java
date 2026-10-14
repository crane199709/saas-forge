package io.saas.forge.tenantaccess.application.tenant;

import java.util.UUID;

/** 客户可观察的生命周期结果及允许动作，不包含安全工作流材料。 */
public record TenantLifecycleProgress(UUID tenantId, UUID operationId, String action, String state,
        boolean canSuspend, boolean canResume, boolean canRecoverSuspension, boolean canContinue,
        boolean canClose, boolean canRecoverClosure) {
}
