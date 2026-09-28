# Complete Compose Integration Acceptance

> The current Console lives in independent saas-forge-web. This page retains backend environment and bootstrap operations; see the [cleanup inventory](../../docs/acceptance/issue-207-cleanup.md) for coverage ownership.

This directory composes complete integration acceptance from service-owned definitions and restores startup gates. For independent daily lifecycles, see the [environment guide](../compose/README-en.md). Maintenance commands below target the acceptance project. Use a dedicated `.env` and absolute Secret paths; do not copy old relative paths unchanged.


For daily application development, start with the [native development guide](../../docs/native-local-development.md): foreground `pnpm run dev` and IDE Run/Debug. Full Compose and replacement tools below are for integration acceptance, demos and reproduction. Infrastructure may be prepared independently; full application orchestration is not required for daily startup.

[简体中文](README.md)

This directory provides the minimum saas-forge local runtime topology for development, demonstrations, and end-to-end testing. The default `compose.yaml` starts only the backend and infrastructure; it does not include either Console or a browser HTTPS entry point.

## Included components

- Gateway and the IAM, Tenant Access, Entitlement, and Audit domain services
- PostgreSQL 18, Mailpit, and one Flyway migration job per domain service
- Redis, single-node KRaft Kafka, single-node Nacos, and the OpenTelemetry Collector
- Separate named volumes for PostgreSQL, Redis, and Kafka

S3-compatible object storage is outside this topology. Per [ADR 0036](../../docs/adr/0036-tenant-access-owns-controlled-tenant-brand-profiles.md) a minimal capability arrives in phase 4 with tenant brand assets, and phase 6 reuses it for audit exports behind a separate storage boundary. The Collector currently uses only the `debug` exporter; Prometheus, Loki, Tempo, and Grafana are not deployed.

## Start the stack

Run these commands from this directory:

```bash
test -f .env || cp .env.example .env
# Fill every variable in .env with local-development values.
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-acceptance}" bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose config
docker compose up --build
```

The initialization script creates a Git-ignored PKCS#8 RSA private key under `.secrets/`, runs the IAM Flyway migrations, and writes matching local metadata when the database has no ACTIVE Signing Key. It is safe to rerun; if the database already contains a different ACTIVE Key, it refuses to overwrite it and requires an explicit rotation.

On the first start, Nacos initializes with the explicit administrator password in `.env`; `nacos-init` then creates non-default IAM, Tenant Access, Entitlement, Audit, Gateway, and configuration-publisher development identities, the `dev` namespace, and their separate configuration resources in the `SAAS_FORGE` group. PostgreSQL then becomes healthy, the four `*-migrate` jobs migrate their own databases, the domain services start, and Gateway starts last. Compose explicitly passes `NACOS_TLS_ENABLED=false` to every application because it provides a single-node Nacos only on the isolated local network; never reuse this topology, address, or credential set in production. All five applications import only their own resource with `refreshEnabled=false`, so ordinary configuration changes use the controlled publishing process and a rolling deployment; no policy is dynamically refreshed locally. A service is not Ready when its Nacos configuration is absent, Nacos is unavailable, or registration fails; Gateway proxies every current public route only through healthy Nacos instances of its owning service, and Audit registration opens no new route. Access the local Nacos console at <http://127.0.0.1:8849/>. Inspect the status with:

```bash
docker compose ps --all
```

An `Exited (0)` status for a `*-migrate` job means its migration succeeded. The backend already exposes authentication and management APIs. Service roots have no page; a root `404` neither means the APIs are unavailable nor proves service readiness.

## Unified Console and browser verification

The Console is built and run in [saas-forge-web](https://github.com/crane199709/saas-forge-web), using its pinned Client and Node/pnpm toolchain. Run `pnpm run verify` and `pnpm run dev` there; configure the trusted Console/API origins in ignored personal configuration. This backend Compose stack does not build, mount or manage frontend applications.

Use the [independent handoff](../../docs/acceptance/independent-verification.md) to prepare an isolated environment and supply its identity to frontend browser checks. Enable v2 only through the [controlled cutover](../../docs/acceptance/issue-204-unified-console.md#受控切换). Removing old UI entrypoints does not authorize retiring external consumers. Browsers connect to an already running HTTPS Console/Gateway; environment preparation and cleanup remain the operator's responsibility.

TLS Edge still supports independent `start|status|stop edge` commands. Legacy Platform/Tenant/all lifecycle and browser orchestration commands are removed. Remote artifacts are external read-only frontend outputs; backend static-delivery and exact CORS checks remain. See the [cleanup and coverage inventory](../../docs/acceptance/issue-207-cleanup.md) for outstanding Fresh, security, visual and accessibility aggregation. Historical commands remain in Git, not as current operating instructions.

## Fresh-volume Tenant lifecycle acceptance

Run the one-shot acceptance script from the repository root. Every run creates an isolated Compose project, random host ports, temporary Secrets, and fresh PostgreSQL, Redis, and Kafka volumes; it does not read or modify `deploy/compose/.env` or development-stack data:

```bash
bash scripts/verify-tenant-lifecycle-e2e.sh
```

The script builds the current source, explicitly bootstraps the Platform Admin and three reserved service clients, then verifies the initial password change, Quota/Plan setup, PENDING Tenant, Subscription, administrator initialization, Mailpit Password Setup, and Tenant-context login. It also covers missing platform role, wrong scope, unavailable IAM, exhausted quota, expired Tenant, credential conflict, cross-Tenant RLS, and plaintext-sensitive-data boundaries. The temporary Compose project, volumes, and Secrets are removed on both success and failure; never copy temporary credentials into logs or the repository.

## Explicit Platform Admin bootstrap

Normal IAM startup never creates the Platform Admin. The account must be created by the explicit one-shot bootstrap task. Its random initial password is valid only for the first login and must be replaced within 24 hours of creation.

### 1. Configure the Secret file paths

`.env` contains only the external Secret file paths, never the email or password values:

```dotenv
IAM_PLATFORM_ADMIN_EMAIL_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-email
IAM_PLATFORM_ADMIN_PASSWORD_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-password
```

Create the email and random initial-password files from this directory:

```bash
mkdir -p .secrets
printf '%s\n' 'your-administrator-email' > .secrets/platform-admin-email
openssl rand -base64 32 > .secrets/platform-admin-password
chmod 600 .secrets/platform-admin-email .secrets/platform-admin-password
```

Both files must contain non-empty, single-line UTF-8 text and may have one trailing line ending. On macOS, copy the initial password to the clipboard without displaying it in the terminal:

```bash
pbcopy < .secrets/platform-admin-password
```

### 2. Rebuild and start IAM

The normal IAM service and the bootstrap task share `saas.forge/iam-service:local`, so code changes require only one image build:

```bash
docker compose build iam-service
docker compose up -d iam-service gateway
```

If the entire stack has not been started yet, run:

```bash
docker compose up --build -d
```

### 3. Run the one-shot bootstrap

Run the bootstrap profile explicitly:

```bash
docker compose --profile bootstrap run --rm iam-platform-admin-bootstrap
```

The task waits for `iam-migrate` to succeed, then creates the Identity, 24-hour Initial Platform Credential, `PLATFORM_ADMIN` role, idempotency fact, and Outbox event in one IAM database transaction. An identical still-valid state can be replayed safely; any email, credential, or role drift fails without overwriting existing data. Secret files must contain single-line UTF-8 text and may have one trailing line ending. Logs contain only non-sensitive identifiers, expiry, outcome, and Trace ID. Normal `docker compose up` does not enable the `bootstrap` profile and neither mounts nor reads these Secrets.

If Docker reports `bind source path does not exist`, the host Secret files have not been created or their `.env` paths are incorrect. Check the files from this directory without printing their contents:

```bash
test -s .secrets/platform-admin-email &&
test -s .secrets/platform-admin-password &&
echo "Platform Admin Secret files are ready"
```

The bootstrap state intentionally changes after the initial password is replaced. Do not rerun the bootstrap task after a successful password change.

### 4. Log in through the unified Console with the initial password

After meeting the browser prerequisites above, open the [local unified Console](https://console.saas.forge.test/), enter the bootstrap administrator email and initial password, and select “登录” (Log in). This creates only a restricted session and should open “设置新密码” (Set a new password); platform management remains unavailable at this point.

If the initial password has expired and no regular password exists, use the restricted initial-credential reset below rather than rerunning account creation. If the UI reports an active session in the slot, follow its prompt to log out of that Console session first.

### 5. Set the regular password in the UI

Enter the regular password on “设置新密码” and select “更新密码” (Update password). The password must meet all of these rules:

- At least 12 Unicode code points;
- At most 128 Unicode code points and at most 512 UTF-8 bytes;
- No spaces, line endings, tabs, or other Unicode whitespace;
- Must not match the system's compromised-password blocklist.

Success displays “密码已更新，请使用新密码重新登录。” (Password updated; log in with the new password). The initial password and restricted session are invalidated. After confirming success, remove the expired initial-password file from the Compose directory:

```bash
rm .secrets/platform-admin-password
```

For a custom Secret path, remove the corresponding old file. Do not remove active service Client Secrets or signing private keys.

### 6. Log in again and check the session

1. Enter the administrator email and regular password on the login page. After login, select the platform management context from the authoritative candidates when prompted.
2. Reload and confirm that session recovery returns to the home page. If a network failure leaves recovery uncertain, use “重试恢复” (Retry recovery).
3. Select “退出登录” (Log out) and confirm the login page appears. Retry through the UI if logout fails. Reloading should not restore the ended Platform session.

These UI actions call the formal APIs; manual login/password-change requests and Access Token inspection are unnecessary. Never write passwords, Tokens, or cookies to `.env`, Git, logs, or chat messages. The `OAuth Client` menu can now list, create, and show Clients, rotate or recover Secrets, and revoke Clients; a Secret is shown only in the first successful response, and the operation history page never replays it.

## Explicit reserved service OAuth Client bootstrap

Generate three deployment-local fixed Client IDs and Secrets from the Compose directory:

```bash
../../saas-forge-services/iam-service/generate-service-client-secrets.sh "$PWD/.secrets"
```

The script uses `openssl` to generate UUIDv7 Client IDs and 256-bit random Secrets, applies `umask 077`, and refuses to overwrite existing files. Then run the explicit one-shot task:

```bash
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
```

The first run creates all three fixed service identities atomically. After formal rotation, reruns only validate Client ID, service key, fixed scopes, and a mounted Secret matching any currently valid Secret. Expired or revoked mounted Secrets require the external file to be updated; a revoked Client requires the Replacement Job and is never modified or restored by bootstrap. Normal startup does not execute this task, and each runtime service mounts only its own Client ID and Secret. No Secret value is stored in source, images, Compose values, or Nacos configuration.

### Replace a revoked reserved Client

Write a newly generated 256-bit Secret to a restricted single-line file, then provide a canonical UUIDv7 request ID, service key, old Client ID, and new UUIDv7 Client ID:

```bash
export IAM_RESERVED_CLIENT_REPLACEMENT_REQUEST_ID=<uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_SERVICE_KEY=IAM
export IAM_RESERVED_CLIENT_REPLACEMENT_OLD_CLIENT_ID=<revoked-client-uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_NEW_CLIENT_ID=<new-client-uuidv7>
export IAM_RESERVED_CLIENT_REPLACEMENT_SECRET_FILE="$PWD/.secrets/replacement-client-secret"
docker compose --profile service-client-replacement run --rm iam-reserved-service-client-replacement
```

The service key is limited to `IAM`, `TENANT_ACCESS`, or `ENTITLEMENT`; name and scopes are derived from it and cannot be supplied. An exact replay returns `ALREADY_REPLACED`; rebinding the same request ID to different inputs fails for manual handling.

## Restricted Platform Admin initial-credential reset

Only the Default Platform Admin that has not established a regular password can use this restricted reset task. Prepare a new UUIDv7 `resetRequestId` and a new random-password file for each new reset. Reuse the same `resetRequestId` only to replay the same operation:

```dotenv
IAM_PLATFORM_ADMIN_RESET_REQUEST_ID_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-reset-request-id
IAM_PLATFORM_ADMIN_RESET_PASSWORD_FILE=/absolute/path/to/acceptance/.secrets/platform-admin-reset-password
```

```bash
docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d iam_db -Atc "SELECT uuidv7()"' \
  > .secrets/platform-admin-reset-request-id
openssl rand -base64 32 > .secrets/platform-admin-reset-password
chmod 600 \
  .secrets/platform-admin-reset-request-id \
  .secrets/platform-admin-reset-password
docker compose --profile credential-reset run --rm iam-platform-admin-credential-reset
```

The task starts no HTTP server and mounts only these two read-only Secrets. In one IAM database transaction it permanently invalidates all old initial credentials, revokes every `INITIAL_PASSWORD_CHANGE` family, and creates a new 24-hour initial credential, idempotency fact, and Outbox event. An active regular password, inconsistent Default Platform Admin state, or a non-canonical UUIDv7 request ID fails and rolls back the entire operation. Logs contain no password, hash, or Secret content. Delete the obsolete password file after success; a later reset requires both a new request ID and a new password.

> [!IMPORTANT]
> `.env` is for local use only and is ignored by Git. Set one PostgreSQL administrator user and every required variable. Do not commit `.env` or use its local short codes outside local development.

## Local ports

Every host port binds only to `127.0.0.1`; none is exposed to the local network.

| Component               |  Local port | Notes                                                                           |
| ----------------------- | ----------: | ------------------------------------------------------------------------------- |
| Gateway                 |        8080 | HTTP                                                                            |
| IAM                     |        8081 | HTTP                                                                            |
| Tenant Access           |        8082 | HTTP                                                                            |
| Entitlement             |        8083 | HTTP                                                                            |
| Audit                   |        8084 | HTTP                                                                            |
| PostgreSQL              |        5432 | Database connection                                                             |
| Redis                   |        6379 | Authenticate with `REDIS_PASSWORD`                                              |
| Kafka                   |       29092 | Host external listener; containers use `kafka:9092`                             |
| Mailpit                 | 1025 / 8025 | Development SMTP / mail web UI                                                  |
| Nacos                   | 8848 / 8849 | Configuration and service-discovery API / local console; local development only |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP                                                                |

## Environment variables

`.env.example` lists the required variable names but provides no default passwords. `POSTGRES_ADMIN_USER` is the PostgreSQL bootstrap administrator; the JWT issuer, Key Version reference, and local private-key path have development defaults within the local security boundary. The remaining values are passwords or Nacos authentication material.

| Service              | migrator password                 | app password                                                                                                                         |
| -------------------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| PostgreSQL bootstrap | `POSTGRES_ADMIN_PASSWORD`         | —                                                                                                                                    |
| IAM                  | `IAM_MIGRATOR_PASSWORD`           | `IAM_APP_PASSWORD`                                                                                                                   |
| Tenant Access        | `TENANT_ACCESS_MIGRATOR_PASSWORD` | `TENANT_ACCESS_APP_PASSWORD`                                                                                                         |
| Entitlement          | `ENTITLEMENT_MIGRATOR_PASSWORD`   | `ENTITLEMENT_APP_PASSWORD`                                                                                                           |
| Audit                | `AUDIT_MIGRATOR_PASSWORD`         | `AUDIT_APP_PASSWORD`                                                                                                                 |
| Redis                | `REDIS_PASSWORD`                  | —                                                                                                                                    |
| Nacos                | `NACOS_BOOTSTRAP_PASSWORD`        | `NACOS_IAM_PASSWORD`, `NACOS_TENANT_ACCESS_PASSWORD`, `NACOS_ENTITLEMENT_PASSWORD`, `NACOS_AUDIT_PASSWORD`, `NACOS_GATEWAY_PASSWORD` |

`NACOS_IAM_USERNAME`, `NACOS_TENANT_ACCESS_USERNAME`, `NACOS_ENTITLEMENT_USERNAME`, `NACOS_AUDIT_USERNAME`, and `NACOS_GATEWAY_USERNAME` must be non-default development identities. Fill `NACOS_AUTH_IDENTITY_KEY`, `NACOS_AUTH_IDENTITY_VALUE`, and `NACOS_AUTH_TOKEN` with local-only random values; `NACOS_AUTH_TOKEN` must be a Base64 string generated from at least 32 raw characters. `nacos-init` uses the bootstrap administrator identity only to create the namespace, users, and permissions, then uses `NACOS_PUBLISH_USERNAME` to publish the manifest. Issue #130 target identities may additionally read only their own healthy instances for local-replacement verification; Gateway may read only itself and healthy `iam-service`, `tenant-access-service`, and `entitlement-service` instances. See [`../nacos/README.md`](../nacos/README.md) for the full manifest, CI publishing, and emergency reconciliation process.

On initial PostgreSQL volume creation, `bootstrap.sh` creates `iam_db`, `tenant_access_db`, `entitlement_db`, and `audit_db`, with separate `*_migrator` and `*_app` accounts for each service. Migration jobs use migrator accounts; runtime services use app accounts.

## Nacos failure-recovery acceptance

After preparing the local `.env`, run this from the repository root:

```bash
bash scripts/verify-nacos-failure-recovery.sh
```

The script uses a separate Compose project and `failure-recovery.override.yaml`; it neither claims the development stack's host ports nor stops its containers. It verifies that Gateway returns `503` with no healthy IAM instance and has no static-address fallback, that a running Gateway continues using known healthy instances during a brief Nacos outage, and that a new IAM instance cannot start without its required configuration. On exit it removes only containers and volumes created for the isolated acceptance project.

## Stop, clean up, and redeploy

Run these commands from `deploy/compose`; they apply to the default development stack described here. Confirm the target first:

```bash
docker compose ls
docker compose ps --all
```

If the previous deployment used `-p`, `--env-file`, or additional `-f` files, use the same arguments when inspecting, stopping, removing, and starting it. Otherwise, you may target the wrong project or leave old containers behind. The signing-key initialization script below supports the corresponding `COMPOSE_PROJECT_NAME`, `LOCAL_COMPOSE_ENV_FILE`, and `LOCAL_COMPOSE_OVERRIDE_FILE` environment variables; set them consistently for custom projects. Do not substitute global `docker system prune` or `docker volume prune` for project-specific cleanup.

### 1. Pause without redeploying

```bash
docker compose stop
# Resume the existing containers later.
docker compose start
```

These commands retain containers and data. They neither rebuild images nor apply source or Compose configuration changes.

### 2. Rebuild and redeploy while retaining business data

Use this procedure to deploy updated source or deployment configuration. Migrations may change existing database structures; back up any data you need and confirm that it can be restored first.

```bash
docker compose config --quiet
docker compose down
docker compose up --build -d
docker compose ps --all
```

Without `--volumes`, the PostgreSQL, Redis, and Kafka named volumes remain. Existing platform accounts, regular passwords, and Tenant data remain usable, and migrations run before services start. Do not recreate the Platform Admin or regenerate active service Client Secrets, signing private keys, or database passwords. Investigate Flyway checksum mismatches against migration history rather than deleting volumes, rewriting history, or disabling validation to make startup succeed.

The default Compose stack has no persistent volumes for Nacos or Mailpit. Recreating their containers reinitializes Nacos through `nacos-init` from repository configuration and loses old Mailpit messages. Save required Nacos changes through the [Nacos management process](../nacos/README.md) first. Resend missing password-setup emails through the formal API rather than bootstrapping the administrator again.

### 3. Delete local business data and initialize from scratch

Use this only for disposable local development data. For a code update alone, use the previous section.

> [!CAUTION]
> The following `down --volumes` deletes this Compose project's PostgreSQL, Redis, and Kafka named volumes, including all accounts, Tenants, subscriptions, audit records, sessions, and messages. Back up required data and confirm it is recoverable first; restarting cannot recover deleted data.

```bash
docker compose down --volumes
```

This does not remove host `.env`, `.secrets/`, external Secrets, TLS certificates, frontend `dist` directories, or built images. An empty database does not mean credential files have been removed. Before initializing again:

- Retain and review `.env`; do not overwrite it with `.env.example`.
- Complete sets of the three service Client ID/Secret pairs can be reused for this reset local environment. Rerun the service Client bootstrap below to register them in the new database. Run `../../saas-forge-services/iam-service/generate-service-client-secrets.sh "$PWD/.secrets"` only when all six files are absent; it refuses to overwrite files. Restore complete material if some files are missing rather than mixing old and new files.
- The local IAM signing private key can be retained. The initialization script registers matching Signing Key metadata in the new database. If retaining the database, never force regeneration by deleting its private key.
- Check the administrator email file and generate a new random initial password for this deployment. The example below uses default Secret paths; use the actual configured files for custom `.env` paths. If the email file is absent, create it using the earlier Secret-file instructions first.

```bash
mkdir -p .secrets
test -s .secrets/platform-admin-email
openssl rand -base64 32 > .secrets/platform-admin-password
chmod 600 .secrets/platform-admin-email .secrets/platform-admin-password
```

Once all files are ready, run these steps in order. Resolve any failure before continuing with bootstrap or login:

```bash
docker compose config --quiet
docker compose build
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-acceptance}" bash ../../scripts/initialize-local-iam-signing-key.sh
docker compose --profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap
docker compose --profile bootstrap run --rm iam-platform-admin-bootstrap
docker compose up -d
docker compose ps --all
```

Because the database was reset, bootstrap the administrator again and change its initial password within 24 hours. The previous regular password no longer works. Recreate supported business resources through the unified Console; data preparation through APIs is not evidence of page acceptance.

### 4. Update the frontend and verify the combination

Build and publish one Console artifact using the frontend repository instructions. Compatible backend updates do not force a frontend release. Record both source/artifact versions, the pinned Client and this environment's evidence. Use established credentials for retained data, or complete the initial password change for a new environment. Verify login, context selection, refresh and global logout through the independent handoff; a running container is not business acceptance.
