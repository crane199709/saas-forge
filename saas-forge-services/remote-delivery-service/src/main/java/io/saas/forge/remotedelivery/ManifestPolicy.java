package io.saas.forge.remotedelivery;

import java.util.Map;
import java.util.UUID;

/** 来源和模块授权由部署边界决定，注册声明不能扩展它们。 */
final class ManifestPolicy {
    static final String REGISTER_SCOPE = "remote-delivery:manifest:register";
    private final String remoteOrigin;
    private final Map<String, UUID> moduleClients;
    ManifestPolicy(String rootDomain, Map<String, UUID> moduleClients) {
        if (rootDomain == null || !rootDomain.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                || !rootDomain.contains(".") || rootDomain.contains("..")) {
            throw new IllegalArgumentException("browser.rootDomain 必须为受控域名");
        }
        this.remoteOrigin = "https://remote." + rootDomain;
        this.moduleClients = Map.copyOf(moduleClients);
        this.moduleClients.forEach((module, client) -> {
            if (!module.matches("[a-z][a-z0-9-]{1,62}") || client.version() != 7)
                throw new IllegalArgumentException("CI 模块授权配置非法");
        });
    }
    void requireRegistration(UUID client, RegisterManifestRequest request) {
        if (!client.equals(moduleClients.get(request.module()))) throw new ManifestException(403, "CI_MODULE_NOT_ALLOWED");
        requireSource(request);
    }
    void requireSource(RegisterManifestRequest request) {
        String expected = remoteOrigin + "/" + request.module() + "/" + request.version() + "/remote.js";
        if (!expected.equals(request.source())) throw new ManifestException(400, "REMOTE_SOURCE_NOT_ALLOWED");
    }
}
