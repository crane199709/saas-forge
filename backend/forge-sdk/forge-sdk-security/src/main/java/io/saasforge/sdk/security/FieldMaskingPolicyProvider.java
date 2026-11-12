package io.saasforge.sdk.security;

import java.util.Map;
import java.util.UUID;

/**
 * 向导出、日志与在线通道提供当前服务的租户生效规则，不依赖任一执行通道的类型。
 *
 * <p>提供方从可信服务身份限定服务范围，不能由调用方选择另一服务；提供方不可用时必须抛出不含原文的异常，
 * 不得伪装为空快照。端口不负责 IAM HTTP、缓存或通道适配。
 */
@FunctionalInterface
public interface FieldMaskingPolicyProvider {
    /**
     * 获取一次执行所使用的不可变规则快照，提供方须通过 {@link Map#copyOf} 或等价方式防止后续修改。
     *
     * <p>键为字段路径；已声明但缺规则的字段仍保留记录及 null rule，以区分未声明字段。
     * 执行通道应在一次导出或在线响应中复用同一快照，避免处理中途配置变化。
     *
     * @param tenantId 已验证的 Tenant ID，不得为 null
     * @return 仅含当前服务字段的不可变快照；没有声明时为空映射
     */
    Map<String, EffectiveFieldPolicy> policies(UUID tenantId);
}
