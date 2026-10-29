package io.saasforge.iam.permission.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时登记 iam 自身的租户侧与平台侧清单；同一版本重复登记为无操作，多实例并发启动由清单的服务锁串行。 */
@Component
class IamManifestRegistrar implements ApplicationRunner {
    private final PermissionService permissions;

    IamManifestRegistrar(PermissionService permissions) {
        this.permissions = permissions;
    }

    @Override
    public void run(ApplicationArguments args) {
        permissions.report(IamPermissions.SERVICE, IamPermissions.manifest());
        permissions.report(PlatformPermissions.SERVICE, PlatformPermissions.manifest());
    }
}
