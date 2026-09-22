import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createServer } from "node:net";
import test from "node:test";

import {
  additionalExecutableJar,
  reusableContainerStartArguments,
  additionalReadiness,
  assertGrpcHostPortPlan,
  assertSupportedService,
  additionalServiceDefinition,
  callersAreReady,
  classifyServiceState,
  diagnosticSuccessCode,
  formatServiceStatus,
  localIamCallerEnvironment,
  localIamEnvironment,
  localAdditionalEnvironment,
  nacosHosts,
  parseComposePs,
  stateOf,
} from "../local-service-replacement.mjs";
import { edgeStartArguments } from "../local-https-development.mjs";

test("requires the fixed, unique loopback gRPC host-port assignments", () => {
  assert.doesNotThrow(() =>
    assertGrpcHostPortPlan({
      iam: 9091,
      tenantAccess: 9092,
      entitlement: 9093,
    }),
  );
  assert.throws(
    () =>
      assertGrpcHostPortPlan({
        iam: 9091,
        tenantAccess: 9091,
        entitlement: 9093,
      }),
    /tenant-access-service.*9092/u,
  );
});

test("does not inject downstream addresses into container callers", () => {
  assert.deepEqual(localIamCallerEnvironment(), {
    "entitlement-service": {},
    "tenant-access-service": {},
  });
});

test("requires every recreated caller to register exactly one healthy Nacos instance", () => {
  assert.equal(
    callersAreReady({
      "entitlement-service": [{ ip: "172.19.0.8", port: 8080 }],
      "tenant-access-service": [{ ip: "172.19.0.7", port: 8080 }],
    }),
    true,
  );
  assert.equal(
    callersAreReady({
      "entitlement-service": [],
      "tenant-access-service": [{ ip: "172.19.0.7", port: 8080 }],
    }),
    false,
  );
});

test("requires one explicit supported service target", () => {
  assert.doesNotThrow(() => assertSupportedService("iam-service"));
  assert.doesNotThrow(() => assertSupportedService("gateway"));
  assert.doesNotThrow(() => assertSupportedService("tenant-access-service"));
  assert.doesNotThrow(() => assertSupportedService("entitlement-service"));
  assert.doesNotThrow(() => assertSupportedService("audit-service"));
  assert.throws(() => assertSupportedService("runtime"), /必须显式/u);
  assert.throws(() => assertSupportedService(undefined), /必须显式/u);
});

test("declares a fixed module and host port for each additional service", () => {
  assert.deepEqual(
    Object.fromEntries(
      [
        "gateway",
        "tenant-access-service",
        "entitlement-service",
        "audit-service",
      ].map((service) => {
        const definition = additionalServiceDefinition(service);
        return [
          service,
          [definition.module, definition.httpPort, definition.grpcPort],
        ];
      }),
    ),
    {
      "audit-service": ["saas-forge-services/audit-service", 8084, undefined],
      "entitlement-service": [
        "saas-forge-services/entitlement-service",
        8083,
        9093,
      ],
      gateway: ["gateway", 8080, undefined],
      "tenant-access-service": [
        "saas-forge-services/tenant-access-service",
        8082,
        9092,
      ],
    },
  );
});

test("selects the Tenant Access executable JAR without its Maven test fixture", () => {
  assert.equal(
    additionalExecutableJar(
      [
        "tenant-access-service-0.1.0-SNAPSHOT-test-fixture.jar",
        "tenant-access-service-0.1.0-SNAPSHOT.jar",
        "tenant-access-service-0.1.0-SNAPSHOT.jar.original",
      ],
      "tenant-access-service",
    ),
    "tenant-access-service-0.1.0-SNAPSHOT.jar",
  );
});

test("reuses the stopped target container before asking Compose to recreate it", () => {
  assert.deepEqual(reusableContainerStartArguments({ ID: "fbc8cc0b49bd" }), [
    "start",
    "fbc8cc0b49bd",
  ]);
  assert.equal(
    reusableContainerStartArguments({ ID: "not-a-container" }),
    undefined,
  );
  assert.equal(reusableContainerStartArguments(undefined), undefined);
});

test("maps a local Gateway only to existing loopback application ports", () => {
  const definition = additionalServiceDefinition("gateway");
  const environment = localAdditionalEnvironment(
    definition,
    {
      IAM_JWT_ISSUER: "https://api.saas.forge.test",
      NACOS_GATEWAY_PASSWORD: "gateway-password",
      NACOS_GATEWAY_USERNAME: "gateway-user",
      SPRING_DATA_REDIS_PASSWORD: "redis-password",
    },
    { dockerHostAddress: "192.168.65.254", nacosPort: 8848, secretFiles: {} },
  );
  assert.equal(environment.SERVER_PORT, "8080");
  assert.equal(environment.SPRING_CLOUD_NACOS_DISCOVERY_IP, "192.168.65.254");
  assert.equal(environment.SPRING_DATA_REDIS_HOST, "127.0.0.1");
  assert.equal(environment.SAASFORGE_LOCAL_REPLACEMENT_ENABLED, "true");
  assert.equal(
    environment.SAASFORGE_LOCAL_REPLACEMENT_IAM_SERVICE_PORT,
    "8081",
  );
  assert.equal(
    environment.SAASFORGE_LOCAL_REPLACEMENT_TENANT_ACCESS_SERVICE_PORT,
    "8082",
  );
  assert.equal(
    environment.SAASFORGE_LOCAL_REPLACEMENT_ENTITLEMENT_SERVICE_PORT,
    "8083",
  );
});

test("keeps infrastructure settings without injecting downstream service addresses", () => {
  const environment = {
    AUDIT_DATABASE_PASSWORD: "audit-password",
    AUDIT_DATABASE_USERNAME: "audit-user",
    IAM_JWT_ISSUER: "https://api.saas.forge.test",
    NACOS_AUDIT_PASSWORD: "audit-nacos-password",
    NACOS_AUDIT_USERNAME: "audit-nacos-user",
    NACOS_ENTITLEMENT_PASSWORD: "entitlement-nacos-password",
    NACOS_ENTITLEMENT_USERNAME: "entitlement-nacos-user",
    NACOS_TENANT_ACCESS_PASSWORD: "tenant-nacos-password",
    NACOS_TENANT_ACCESS_USERNAME: "tenant-nacos-user",
    SPRING_DATA_REDIS_PASSWORD: "redis-password",
    SPRING_DATASOURCE_PASSWORD: "database-password",
    SPRING_DATASOURCE_USERNAME: "database-user",
  };
  const inputs = {
    dockerHostAddress: "192.168.65.254",
    nacosPort: 8848,
    secretFiles: {
      "/run/secrets/iam-service-client-id": "/secure/iam-client-id",
      "/run/secrets/service-client-id": "/secure/service-client-id",
      "/run/secrets/service-client-secret": "/secure/service-client-secret",
    },
  };

  const tenant = localAdditionalEnvironment(
    additionalServiceDefinition("tenant-access-service"),
    environment,
    inputs,
  );
  const entitlement = localAdditionalEnvironment(
    additionalServiceDefinition("entitlement-service"),
    environment,
    inputs,
  );
  const audit = localAdditionalEnvironment(
    additionalServiceDefinition("audit-service"),
    environment,
    inputs,
  );

  assert.equal(tenant.SAASFORGE_SECRETS_IMPORT, "");
  assert.equal(entitlement.SAASFORGE_SECRETS_IMPORT, "");
  assert.equal(audit.SAASFORGE_SECRETS_IMPORT, "");
  assert.equal(tenant.IAM_GRPC_ADDRESS, undefined);
  assert.equal(tenant.ENTITLEMENT_GRPC_ADDRESS, undefined);
  assert.equal(entitlement.IAM_GRPC_ADDRESS, undefined);
  assert.equal(entitlement.TENANT_ACCESS_GRPC_ADDRESS, undefined);
  assert.equal(audit.KAFKA_BOOTSTRAP_SERVERS, "127.0.0.1:29092");
  assert.equal(
    audit.AUDIT_DATABASE_URL,
    "jdbc:postgresql://127.0.0.1:5432/audit_db",
  );
});

test("keeps Gateway discovery to public route targets and grants each target self-observation", async () => {
  const nacosInit = await readFile(
    new URL("../../deploy/compose/nacos-init.sh", import.meta.url),
    "utf8",
  );
  const start = nacosInit.indexOf("# gateway-discovery-permissions: begin");
  const end = nacosInit.indexOf("# gateway-discovery-permissions: end");
  assert.ok(start >= 0 && end > start);
  const gatewayPermissions = nacosInit.slice(start, end);

  assert.match(gatewayPermissions, /naming\/gateway:r/u);
  assert.match(gatewayPermissions, /naming\/iam-service:r/u);
  assert.match(gatewayPermissions, /naming\/tenant-access-service:r/u);
  assert.match(gatewayPermissions, /naming\/entitlement-service:r/u);
  assert.doesNotMatch(gatewayPermissions, /naming\/audit-service:r/u);
  for (const service of [
    "gateway",
    "tenant-access-service",
    "entitlement-service",
    "audit-service",
  ]) {
    assert.match(nacosInit, new RegExp(`naming/${service}:r`, "u"));
  }
});

test("uses a listening local port as Gateway readiness without exposing an Actuator route", async (t) => {
  const server = createServer((socket) => socket.destroy());
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(() => server.close());
  const address = server.address();
  assert.equal(typeof address, "object");

  assert.equal(
    await additionalReadiness({ httpPort: address.port, service: "gateway" }),
    true,
  );
});

test("recreates only the stateless Edge before checking trusted HTTPS", async () => {
  assert.deepEqual(edgeStartArguments("/workspace").slice(-5), [
    "up",
    "--detach",
    "--no-deps",
    "--force-recreate",
    "local-https-edge",
  ]);
});

test("classifies container, local, unavailable, and duplicate IAM states", () => {
  assert.equal(
    classifyServiceState({
      containerRunning: true,
      healthyInstances: 1,
      localProcessRunning: false,
      localReady: false,
      localRegistered: false,
    }),
    "CONTAINER",
  );
  assert.equal(
    classifyServiceState({
      containerRunning: false,
      healthyInstances: 1,
      localProcessRunning: true,
      localReady: true,
      localRegistered: true,
    }),
    "LOCAL",
  );
  assert.equal(
    classifyServiceState({
      containerRunning: false,
      healthyInstances: 0,
      localProcessRunning: false,
      localReady: false,
      localRegistered: false,
    }),
    "UNAVAILABLE",
  );
  assert.equal(
    classifyServiceState({
      containerRunning: true,
      healthyInstances: 2,
      localProcessRunning: true,
      localReady: true,
      localRegistered: true,
    }),
    "DUPLICATE",
  );
});

test("formats shareable status with fixed ports, readiness, and Nacos count", () => {
  assert.equal(
    formatServiceStatus({
      service: "tenant-access-service",
      state: "LOCAL",
      httpPort: 8082,
      grpcPort: 9092,
      healthyInstances: 1,
    }),
    "STATUS: tenant-access-service LOCAL http=8082 grpc=9092 readiness=READY nacos=1",
  );
  assert.equal(
    formatServiceStatus({
      service: "audit-service",
      state: "DUPLICATE",
      httpPort: 8084,
      grpcPort: undefined,
      healthyInstances: 2,
    }),
    "STATUS: audit-service DUPLICATE http=8084 readiness=NOT_READY nacos=2",
  );
  assert.equal(
    formatServiceStatus({
      service: "gateway",
      state: "UNAVAILABLE",
      httpPort: 8080,
      grpcPort: undefined,
      healthyInstances: "UNAVAILABLE",
    }),
    "STATUS: gateway UNAVAILABLE http=8080 readiness=NOT_READY nacos=UNAVAILABLE",
  );
});

test("uses positive diagnostic labels without weakening failure classifications", () => {
  assert.equal(diagnosticSuccessCode("MIGRATION_FAILED"), "MIGRATION");
  assert.equal(diagnosticSuccessCode("SECRET_MISSING"), "SECRET");
  assert.equal(diagnosticSuccessCode("SIGNING_KEY_INVALID"), "SIGNING_KEY");
  assert.equal(diagnosticSuccessCode("NACOS_UNAVAILABLE"), "NACOS");
});

test("parses Compose JSON-lines status without reading application logs", () => {
  assert.deepEqual(
    parseComposePs(
      [
        '{"Service":"iam-service","State":"running","ExitCode":0}',
        '{"Service":"iam-migrate","State":"exited","ExitCode":0}',
      ].join("\n"),
    ),
    [
      { Service: "iam-service", State: "running", ExitCode: 0 },
      { Service: "iam-migrate", State: "exited", ExitCode: 0 },
    ],
  );
});

test("prefers a running Compose entry when an old recreated container remains", () => {
  assert.equal(
    stateOf(
      [
        { Service: "iam-service", State: "created" },
        { Service: "iam-service", State: "running" },
      ],
      "iam-service",
    )?.State,
    "running",
  );
});

test("accepts only enabled and healthy IPv4 Nacos hosts", () => {
  assert.deepEqual(
    nacosHosts({
      data: {
        hosts: [
          { ip: "192.168.65.254", port: 8081, healthy: true, enabled: true },
          { ip: "192.168.65.2", port: 8080, healthy: false, enabled: true },
          { ip: "not-an-ip", port: 8080, healthy: true, enabled: true },
        ],
      },
    }),
    [{ ip: "192.168.65.254", port: 8081 }],
  );
  assert.deepEqual(
    nacosHosts({
      data: [
        { ip: "192.168.65.254", port: 8081, healthy: true, enabled: true },
      ],
    }),
    [{ ip: "192.168.65.254", port: 8081 }],
  );
  assert.deepEqual(nacosHosts({ data: [] }), []);
});

test("maps only IAM runtime settings to host-reachable infrastructure", () => {
  const environment = localIamEnvironment(
    {
      environment: {
        BROWSER_ROOT_DOMAIN: "saas.forge.test",
        IAM_JWT_ISSUER: "https://api.saas.forge.test",
        IAM_JWT_PEM_KEY_VERSION_REF: "local/dev/pem/1",
        NACOS_IAM_PASSWORD: "iam-password",
        NACOS_IAM_USERNAME: "iam-dev",
        PASSWORD_SETUP_PAGE_URI:
          "https://console.saas.forge.test/password-setup",
        SMTP_FROM: "no-reply@saas.forge.test",
        SPRING_DATA_REDIS_PASSWORD: "redis-password",
        SPRING_DATASOURCE_PASSWORD: "iam-app-password",
        SPRING_DATASOURCE_USERNAME: "iam_app",
      },
      grpcPorts: { iam: 9091, tenantAccess: 9092, entitlement: 9093 },
      nacosGrpcPort: 9848,
      nacosPort: 8848,
      serviceClientIdFile: "/secure/iam-client-id",
      serviceClientSecretFile: "/secure/iam-client-secret",
      signingKeyFile: "/secure/iam-key.pem",
    },
    "192.168.65.254",
  );

  assert.equal(environment.SAASFORGE_SECRETS_IMPORT, "");
  assert.equal(environment.SERVER_PORT, "8081");
  assert.equal(environment.SPRING_GRPC_SERVER_PORT, "9091");
  assert.equal(environment.SPRING_CLOUD_NACOS_DISCOVERY_IP, "192.168.65.254");
  assert.equal(environment.NACOS_SERVER_ADDR, "127.0.0.1:8848");
  assert.equal(
    environment.SPRING_DATASOURCE_URL,
    "jdbc:postgresql://127.0.0.1:5432/iam_db",
  );
  assert.equal(environment.SPRING_DATA_REDIS_HOST, "127.0.0.1");
  assert.equal(environment.KAFKA_BOOTSTRAP_SERVERS, "127.0.0.1:29092");
  assert.equal(environment.SMTP_HOST, "127.0.0.1");
  assert.equal(environment.TENANT_ACCESS_GRPC_ADDRESS, undefined);
  assert.equal(
    environment.IAM_JWT_PEM_PRIVATE_KEY_LOCATION,
    "file:/secure/iam-key.pem",
  );
});
