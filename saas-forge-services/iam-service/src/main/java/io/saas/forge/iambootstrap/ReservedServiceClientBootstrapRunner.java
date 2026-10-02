package io.saas.forge.iambootstrap;

import io.saas.forge.iam.application.bootstrap.ReservedServiceClient;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapInput;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapResult;
import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class ReservedServiceClientBootstrapRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReservedServiceClientBootstrapRunner.class);

    private final ReservedServiceClientBootstrapService service;
    private final SecretTextFileReader reader;
    private final Path iamIdFile;
    private final Path iamSecretFile;
    private final Path tenantAccessIdFile;
    private final Path tenantAccessSecretFile;
    private final Path entitlementIdFile;
    private final Path entitlementSecretFile;
    private final Path remoteDeliveryIdFile;
    private final Path remoteDeliverySecretFile;

    ReservedServiceClientBootstrapRunner(
            ReservedServiceClientBootstrapService service,
            SecretTextFileReader reader,
            Path iamIdFile,
            Path iamSecretFile,
            Path tenantAccessIdFile,
            Path tenantAccessSecretFile,
            Path entitlementIdFile,
            Path entitlementSecretFile) {
        this(service,reader,iamIdFile,iamSecretFile,tenantAccessIdFile,tenantAccessSecretFile,entitlementIdFile,entitlementSecretFile,null,null);
    }

    ReservedServiceClientBootstrapRunner(ReservedServiceClientBootstrapService service,SecretTextFileReader reader,
            Path iamIdFile,Path iamSecretFile,Path tenantAccessIdFile,Path tenantAccessSecretFile,
            Path entitlementIdFile,Path entitlementSecretFile,Path remoteDeliveryIdFile,Path remoteDeliverySecretFile) {
        if ((remoteDeliveryIdFile == null) != (remoteDeliverySecretFile == null))
            throw new IllegalArgumentException("Remote Delivery 引导 ID 和 Secret 文件必须共同提供");
        this.remoteDeliveryIdFile = remoteDeliveryIdFile;
        this.remoteDeliverySecretFile = remoteDeliverySecretFile;
        this.service = service;
        this.reader = reader;
        this.iamIdFile = iamIdFile;
        this.iamSecretFile = iamSecretFile;
        this.tenantAccessIdFile = tenantAccessIdFile;
        this.tenantAccessSecretFile = tenantAccessSecretFile;
        this.entitlementIdFile = entitlementIdFile;
        this.entitlementSecretFile = entitlementSecretFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        var inputs = new java.util.ArrayList<>(List.of(
                input(ReservedServiceClient.IAM, iamIdFile, iamSecretFile),
                input(ReservedServiceClient.TENANT_ACCESS, tenantAccessIdFile, tenantAccessSecretFile),
                input(ReservedServiceClient.ENTITLEMENT, entitlementIdFile, entitlementSecretFile)));
        if (remoteDeliveryIdFile != null) inputs.add(input(ReservedServiceClient.REMOTE_DELIVERY,remoteDeliveryIdFile,remoteDeliverySecretFile));
        ReservedServiceClientBootstrapResult result = service.bootstrap(inputs);
        result.clients().forEach((client, value) -> LOGGER.info(
                "Reserved service OAuth Client bootstrap service={} clientId={} outcome={}",
                client.displayName(), value.clientId(), value.outcome()));
    }

    private ReservedServiceClientBootstrapInput input(
            ReservedServiceClient serviceClient, Path idFile, Path secretFile) {
        String rawId = reader.read(idFile, 36);
        UUID id = UUID.fromString(rawId);
        if (id.version() != 7 || !id.toString().equals(rawId)) {
            throw new IllegalArgumentException("保留 OAuth Client ID 必须是规范 UUIDv7");
        }
        return new ReservedServiceClientBootstrapInput(serviceClient, id, reader.read(secretFile, 43));
    }
}
