package io.saasforge.iam.permission.service;

import io.saasforge.sdk.core.ResourceManifest;

/** 清单附加业务约束；实现不得反向依赖 PermissionService，避免运行时和功能包的依赖环。 */
public interface ManifestPolicy {
    /** 在存储事务内校验，引用修改的并发锁必须持续至该事务结束。 */
    void validate(ResourceManifest manifest);
    /** 新版本已写入但尚未提交；仅记录登记变化，不接收业务字段原文。 */
    void registered(ResourceManifest previous, ResourceManifest current);
}
