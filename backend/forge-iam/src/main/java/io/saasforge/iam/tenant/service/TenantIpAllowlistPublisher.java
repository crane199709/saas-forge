package io.saasforge.iam.tenant.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 必须先完成 IAM 全量发布再启用网关校验；发布失败即启动失败，避免漏规则。 */
@Component
class TenantIpAllowlistPublisher implements ApplicationRunner {
    private final TenantIpAllowlistService allowlists;

    TenantIpAllowlistPublisher(TenantIpAllowlistService allowlists) {
        this.allowlists = allowlists;
    }

    @Override
    public void run(ApplicationArguments args) {
        allowlists.publishAll();
    }
}
