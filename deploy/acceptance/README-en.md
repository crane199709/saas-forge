# Complete Compose Integration Acceptance

> 2026-09-22: Legacy Console builds and browser acceptance entry points have been exported. Related commands below are historical, not current backend instructions. Backend migrations, bootstrap and service acceptance remain available. See [the extraction record](../../docs/acceptance/consoles-extraction.md).

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

## Operations available in the UI

These operations require deployed frontends, HTTPS entry points, healthy backend services, and the appropriate account data. Running `docker compose up` alone does not make the product consoles available.

| Operation                                                                                                    | Current entry point                    | Prerequisite or boundary                                                                                                               |
| ------------------------------------------------------------------------------------------------------------ | -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Platform Admin login, initial password change, subsequent login, and logout                                  | Platform Console                       | Run the administrator bootstrap below first; use the initial password within 24 hours                                                  |
| Session recovery after reload and coordination across tabs                                                   | Platform / Tenant Console              | An established session; platform and tenant sessions are managed separately                                                            |
| Tenant login, selection, switching, and logout                                                               | Tenant Console                         | Prepare accessible Tenants and Memberships through backend APIs first; selection or switching requires multiple accessible Memberships |
| Set a Tenant administrator's first password                                                                  | Password Setup link in a Mailpit email | Administrator initialization must have sent a still-valid link; return to Tenant Console to log in afterward                           |
| Create the Platform Admin or reset its initial credential                                                    | Compose one-shot tasks below           | No platform UI; the restricted reset cannot reset an established regular password                                                      |
| Bootstrap reserved service OAuth Clients or replace revoked Clients                                          | Compose one-shot tasks below           | No platform UI                                                                                                                         |
| Manage OAuth Clients (list, create, detail, Secret rotation and recovery, revocation, operation history)     | Platform Console `/oauth-clients`      | Requires a Platform Admin session; the operation history page only lists committed operations and never replays a Secret |
| Manage Tenants, Plans, and Quota Definitions                                                                | Platform Console `/tenants`, `/plans`, `/quota-definitions` | Requires a Platform Admin session; covers list, creation, and detail                  |
| Manage Subscriptions or initialize Tenant administrators                                                    | Formal backend APIs                    | No platform UI yet; API coverage does not mean corresponding UI features exist        |

The Platform Console home page provides an overview. The Tenant workspace currently shows authentication status only, without a statistics Dashboard or business management actions. See the [Tenant lifecycle acceptance script](../../scripts/verify-tenant-lifecycle-e2e.sh) for API examples; API coverage in that script does not mean corresponding UI features exist.

### Browser access prerequisites

1. Build and separately host `consoles/platform-console/dist` and `consoles/tenant-console-shell/dist`, following the [Console README](../../consoles/README.md). The default Compose stack does not do this.
2. Resolve `platform.saas.forge.test`, `console.saas.forge.test`, and `api.saas.forge.test` to `127.0.0.1`, with browser-trusted TLS on HTTPS port 443. Route the first two hosts to their frontends and proxy the API host to Gateway. Different HTTP localhost ports cannot replace these entry points.
3. Replace `/runtime-config.json` in both deployed frontends with the following content. The original build artifact contains an intentionally invalid template; leaving it unchanged keeps the application on the configuration-error screen.

   ```json
   {
     "schemaVersion": 1,
     "apiBaseUrl": "https://api.saas.forge.test"
   }
   ```

4. Tenant Console serves `/password-setup` and submits through the shared Client to the configured API Origin. The legacy assets `/password-setup/app.js`, `/password-setup/styles.css` and same-origin submission path `/api/v1/auth/password-setups` retain Gateway routing.
5. Complete migrations, wait for backend readiness, and run the administrator and reserved service Client bootstrap tasks below. Tenant operations additionally require a Tenant, Membership, and valid password; a fresh environment does not create this business data automatically.

The browser and shared Client handle cookies, Origin, and Fetch Metadata according to the protocol; UI users do not copy Tokens or cookies. HTTP port `8080` is a local backend port, not a product console. See the [deployment documentation](../../docs/14-deployment.md) for the complete boundary.

### Controlled HTTPS Console development entrypoint

On macOS Docker Desktop, run setup once from the repository root, then choose a Console explicitly:

```bash
bash scripts/local-development.sh setup
pnpm --dir consoles run build:static-remote
bash scripts/local-development.sh doctor
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh status
bash scripts/local-development.sh frontend stop all
```

`setup` reuses a valid local CA and reissues the leaf only when a controlled Host is missing, expiry is within 24 hours, or chain/key validation fails. The certificate covers `platform.saas.forge.test`, `console.saas.forge.test`, `api.saas.forge.test`, and `remote.saas.forge.test`. Existing two-/three-Host installations must rerun setup. Hosts and Keychain changes still require separate explicit interactive authorization; existing configuration is skipped idempotently, and noninteractive system changes are refused.

Every `frontend` invocation requires `start|status|stop` and `platform|tenant|all`. Platform Vite binds to `127.0.0.1:5173` and Tenant to `127.0.0.1:5174`, with strict ports, their respective controlled Hosts, and HMR over each HTTPS Origin's WSS port 443. Edge reaches both loopback Vite servers through `host.docker.internal`, forwards API traffic to the current Gateway, and preserves browser security headers. Unknown Hosts are rejected. Do not widen Vite listeners to all interfaces to work around Docker Desktop connectivity failures.

Start uses Node `24.14.1`, pnpm `11.22.0`, and existing dependencies, reusing a healthy compatible Edge. Daily start/stop never generates certificates, changes hosts/trust, installs dependencies, generates the API client, starts backend services, or resets accounts. An incompatible Edge occupying 443 blocks start; stop both Consoles before upgrading an old Edge and restarting.

`start all` snapshots both Consoles and Edge and runs preflight before starting resources; it succeeds only after both formal HTTPS Hosts are ready. Healthy processes and a compatible Edge are reused without restart. A failed step rolls back only processes and Edge started by that invocation. `stop all` checks both targets before stopping managed Vite processes and the current project Edge; unknown PIDs, port ownership, or invalid Edge configuration block changes.

`frontend status all` reports both fixed ports, HTTPS readiness, and shared Edge without mutation. Top-level `status` prints these first, then runs the existing five backend checks. Normal `STOPPED`, safely identified `STALE`, and transient `STARTING` states do not fail frontend aggregation. `UNREADY`, `UNMANAGED`, Edge `INVALID`, or `UNAVAILABLE` return nonzero. Status output contains no credentials or raw environment-variable values.

Each Console has its own `platform-vite.pid|log` or `tenant-vite.pid|log` under the Git-ignored `deploy/compose/.secrets/local-https-development/` directory. Both PID files and append-only logs use mode 0600. Status is `RUNNING`, `STOPPED`, `STARTING`, `STALE`, `UNMANAGED`, or `UNREADY`. RUNNING requires matching PID, process group, start time, repository, package identity, loopback listener, and formal HTTPS readiness. Stop sends SIGTERM only to a matching target. Edge is retained while another Console is active or has an unknown identity; stopping the last Console only stops the Edge container without deleting containers, backend services, or volumes. Only Platform adopts a matching legacy `vite.pid`, retaining its old log. Stale records are cleaned only after confirming the original process no longer exists.

The package-level `pnpm --filter @saas-forge/tenant-console-shell run dev` command remains available for foreground debugging. If it occupies 5174, managed lifecycle reports UNMANAGED and refuses to terminate it. Foreground HTTP debugging is not controlled HTTPS acceptance.

On the Tenant Origin, `/password-setup` reaches the formal Tenant Console route through Vite. Legacy `/password-setup/app.js`, `/password-setup/styles.css`, and `/api/v1/auth/password-setups` route exactly to the active Gateway, preserving query strings and browser request headers. Gateway controls those asset content types, cache headers, and API error responses. Other Tenant paths and HMR continue to reach Tenant Vite.

These paths share the active target file with the API Host. After `bash scripts/local-development.sh replace gateway`, they follow the local Gateway; after `restore gateway`, they return to the container without changing the browser URL or restarting Edge. A missing or invalid target file or an unreachable target returns 502, with no fallback to Vite or another Gateway; unknown Hosts return 421. When first upgrading these routes, stop both Consoles and start them again as described above to load the new Edge script.

Prepare accounts, Gateway, and backend services separately. This routing capability does not constitute complete Tenant authentication acceptance.

The fourth domain directly serves read-only built artifacts from `consoles/dist/static-remote-acceptance/`, not Vite or API traffic. Only the exact Tenant Origin receives credential-free CORS; missing resources return 404. See the [fourth-domain development guide](../../docs/local-static-remote-development.md) for builds, controlled Edge upgrades, and real Chromium acceptance. This development slice does not complete Fresh Compose or parent specification #155.

#### States and recovery

The old argument-free `bash scripts/local-development.sh frontend` command has been removed and returns a usage error. These nine commands are its complete replacements, run from the repository root:

```bash
bash scripts/local-development.sh frontend start platform
bash scripts/local-development.sh frontend status platform
bash scripts/local-development.sh frontend stop platform
bash scripts/local-development.sh frontend start tenant
bash scripts/local-development.sh frontend status tenant
bash scripts/local-development.sh frontend stop tenant
bash scripts/local-development.sh frontend start all
bash scripts/local-development.sh frontend status all
bash scripts/local-development.sh frontend stop all
```

| State       | Meaning                                                                             | Recovery action                                                                                                                                                                                    |
| ----------- | ----------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RUNNING`   | Managed identity, loopback listener, and formal HTTPS readiness pass                | Develop normally; repeated start reuses resources                                                                                                                                                  |
| `STOPPED`   | No managed record or listener on the target port                                    | Explicitly start when needed                                                                                                                                                                       |
| `STARTING`  | Managed process exists but has no listener within the 30-second startup window      | Wait and check status again; avoid concurrent repeated starts                                                                                                                                      |
| `STALE`     | A record exists, but its original process is gone and the port is unused            | Stop the target to clean safely, then start                                                                                                                                                        |
| `UNMANAGED` | Untrusted process identity or an unknown port owner                                 | Inspect with `lsof -nP -iTCP:5173 -iTCP:5174 -iTCP:443 -sTCP:LISTEN`; have the original operator end their foreground command, then check status. Never kill by port or simply delete the PID file |
| `UNREADY`   | Managed process exists, but listener startup timed out or formal HTTPS is not ready | Check its log and run doctor; resolve Edge/certificate/dependency issues, then explicitly stop and start                                                                                           |

Logs are `deploy/compose/.secrets/local-https-development/platform-vite.log` and `tenant-vite.log`. Inspect locally; do not copy potentially sensitive raw logs into reports. For Edge `UNAVAILABLE`, check Docker Desktop and Docker access permissions first. For `INVALID`/`UNMANAGED`, establish project ownership and configuration before acting; never automatically replace an unknown listener. Doctor recovery guidance does not authorize changes to certificate trust or backends during acceptance.

For direct package-level foreground debugging, use separate terminals in `consoles/`:

```bash
pnpm --filter @saas-forge/platform-console run dev
pnpm --filter @saas-forge/tenant-console-shell run dev
```

Package commands do not generate the API Client; workspace `dev:platform`/`dev:tenant` generate it before starting. Neither participates in managed PID lifecycle; end each with Ctrl-C in its original terminal. A rendered HTTP localhost page proves only frontend rendering, not login, Cookie, CSRF, or TLS security acceptance.

#### Dual-Console product-path acceptance and restoration

1. Before acceptance, save `frontend status all`, top-level `status`, the current project's Edge container identity and running state, and backend container start times. Confirm existing trusted certificates, hosts, dependencies, and ready backends. Do not run setup, bootstrap, backend replace/restore, or password resets in this run.
2. Cover Platform-only, Tenant-only, all, single-target stop, all stop, and repeated operations, reading aggregate status after each step. A single-target stop must retain Edge while the other Console uses it. Frontend stop does not stop backends, delete containers, Secrets, or volumes, or terminate unknown listeners.
3. In one browser context, open `https://platform.saas.forge.test` and `https://console.saas.forge.test` with normal certificate verification. Check page identity, meaningful content, error overlays, console/network, and record actual `/api/*` methods, sanitized paths, and status codes for each Console; the API Origin is `https://api.saas.forge.test`. Never record passwords, Cookies, Tokens, or sensitive response bodies.
4. Use existing accounts or sessions to log in/restore both slots. Refresh one while the other remains usable; log out of one and verify that the other remains signed in after refresh, then reverse roles. Missing login prerequisites are blockers, not permission to create accounts or reset credentials.
5. Open the Password Setup document on the Tenant Origin and verify its script/style resources and an actual form submission reaching the current Gateway. Exercise only a failure path that does not change a password; record a blocker if no safe submission is possible, and do not consume a valid Challenge. An error response proves routing only, not successful password setup.
6. Temporarily change one visible development marker in each Console, observe its controlled WSS Origin connection and HMR update, and restore the files. Verify host listeners bind only to `127.0.0.1` on 5173/5174, reach both from Edge through `host.docker.internal`, and prove direct access via the host LAN address fails. Stop acceptance if Docker Desktop cannot reach loopback Vite; never fall back to `0.0.0.0`.
7. On success or failure, restore development markers and the original managed frontend combination. If only Edge was initially running, frontend stop also stops it: verify the original container identity, then start only that Edge container (`docker start <verified-original-Edge-container-ID>`). Do not force-kill unknown or abnormal processes to restore state. Finally, recheck frontend/Edge states and backend identities/start times read-only, recording any unrestored differences.

Report passed, failed, and blocked checks separately. Script tests or earlier Platform evidence are not Tenant real-machine evidence, and acceptance does not rewrite the historical scope of #126/#131.

`doctor` continues through every category even after a failure. It uses share-safe classifications such as `CERTIFICATE_MISSING`, `CERTIFICATE_EXPIRED`, `CERTIFICATE_UNTRUSTED`, `PORT_CONFLICT`, `MIGRATION_FAILED`, `NACOS_UNAVAILABLE`, `SECRET_MISSING`, `INFRASTRUCTURE_UNAVAILABLE`, and `DUPLICATE_INSTANCE`, followed by a recovery action. It never renders passwords, Tokens, Cookies, Client Secrets, JWT private keys, or raw environment-variable values.

### Local backend-service replacement development

Always select one explicit target; all other application services and infrastructure remain containerized:

```bash
cd ../..
bash scripts/local-development.sh status
bash scripts/local-development.sh replace gateway
# After changing or debugging the local service
bash scripts/local-development.sh restore gateway
```

| Target                  | Fixed loopback HTTP | Fixed loopback gRPC |
| ----------------------- | ------------------: | ------------------: |
| `gateway`               |              `8080` |                   — |
| `iam-service`           |              `8081` |              `9091` |
| `tenant-access-service` |              `8082` |              `9092` |
| `entitlement-service`   |              `8083` |              `9093` |
| `audit-service`         |              `8084` |                   — |

Before stopping a container, `replace` verifies the target's fixed ports, completed migration where applicable, Nacos `dev` configuration, constrained Secrets, infrastructure and dependencies, and exactly one healthy instance under the formal service name. It never prints credentials, cookies, or tokens. An occupied port, inconsistent configuration, duplicate Nacos instance, or readiness failure refuses the cutover. Every local JVM reuses the existing containerized infrastructure through loopback Nacos HTTP `8848`, Nacos 3 gRPC `9848`, PostgreSQL `5432`, Redis `6379`, and Kafka `29092`.

For the four Issue #130 targets, only the selected application container is stopped: no volumes are removed, infrastructure is stopped, or other application containers are recreated. Local Tenant Access and Entitlement call their containerized downstream services through the existing loopback gRPC ports. The local Gateway uses a load-balancer mapping enabled only by `saas.forge.local-replacement.enabled=true` to reach those ports without depending on Docker-internal IPs. Gateway replacement also changes the HTTPS Edge `api.saas.forge.test` target in a Git-ignored restricted file from `gateway:8080` to `host.docker.internal:8080`, without restarting Edge; the file accepts only those fixed targets and port `8080`. Restore returns the Edge target to the container Gateway. IAM retains its existing dedicated caller-recreation behavior to route container callers to local IAM.

If the current development stack was started before the Issue #130 Nacos ACL change, first apply each replacement target's self-only, read-only instance-discovery permission through the controlled initializer; Gateway still discovers only itself and formal public-route targets. Do not alter ACLs manually with an administrator identity:

```bash
cd deploy/compose
docker compose up --detach --no-deps --force-recreate nacos-init
```

`status` prints all five services at once. Each line contains `CONTAINER`, `LOCAL`, `UNAVAILABLE`, or `DUPLICATE`, fixed HTTP/gRPC ports, `READY`/`NOT_READY`, and the healthy Nacos instance count. `restore` terminates the managed local JVM, starts the existing container, and waits until the formal service name is again the sole healthy instance in the `dev` namespace. Repeated calls have no additional effect. The workflow never runs Docker build.

Run the commands directly from a terminal. In an IDE, configure the same command as an External Tool or before-launch task; the repository script still owns the service process so no Secrets, environment values, or personal IDE settings need to be copied. After changing Java, run `restore <target>` and then `replace <target>` to launch the new artifact; calling `replace` while that target is already local intentionally leaves the existing managed process running.

With the trusted local HTTPS entry point and read-only credential files for a Platform Admin with a regular password prepared, run the complete five-service matrix:

```bash
export SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE=/absolute/path/to/platform-email
export SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE=/absolute/path/to/platform-password
bash scripts/verify-local-development-matrix.sh
```

`SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE` must point to a restricted file containing the current regular password, not the initial-password file created by bootstrap; the initial password is invalidated after the first password change. If acceptance needs to retain the current regular password, use a separate Git-ignored file with restricted permissions, and never print its contents in a terminal, log, or chat.

The matrix runs Gateway, IAM, Tenant Access, Entitlement, and Audit through container baseline → local replacement → real operation → container restore → repeated operation. The browser submits the real Platform login form and checks the visible Platform overview, actual `/api/*` requests and responses, console errors, failed requests, and 5xx responses. A separate HTTP localhost page proves that the localhost Origin still receives no credentialed CORS permission. Gateway and Entitlement use the formal Quota Definition operation; IAM uses formal login; Tenant Access uses formal Tenant creation; Audit uses the login-generated `SESSION_STARTED` Committed Fact and verifies that local Audit consumes and persists it. Before and after the matrix, it checks all five application image IDs, the Compose volume set, one container implementation per service, and the Nacos instance counts. Created Tenants, the first `max_users` DRAFT Quota Definition, and audit facts are retained and must not be deleted without confirmation. This is not a Fresh Compose or production workflow.

### Isolated browser acceptance

The repository provides a [Console authentication acceptance script](../../scripts/verify-console-authentication-e2e.sh) and a [dedicated Compose override](console-authentication.override.yaml) for automated checks against a fresh environment. They do not retain an environment for manual exploration and should not be used directly as the default development stack configuration.

Prepare the local DNS entries, trusted certificate, an available `127.0.0.1:443`, Node `24.14.1`, pnpm `11.22.0`, Docker, OpenSSL, Ruby, and Console dependencies. Default local acceptance also requires Playwright Chromium, WebKit, and Chrome to be available and trust the certificate. From the repository root, run:

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/local-cert.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/local-key.pem
bash scripts/verify-console-authentication-e2e.sh --preflight
# After preflight succeeds, build and run full acceptance.
bash scripts/verify-console-authentication-e2e.sh
```

The certificate must cover `platform.saas.forge.test`, `console.saas.forge.test`, `api.saas.forge.test`, and `remote.saas.forge.test`; replace the example absolute paths with actual files. Preflight checks the environment, not successful login, four-domain resource loading, or CORS rejection. The full script creates an isolated random project and fresh volumes, then drives browsers against built Consoles, the real API, and the same `consoles/dist/static-remote-acceptance/` Remote artifacts through four trusted HTTPS Origins. Chromium additionally retains sanitized evidence for Remote requests/responses, credential-free loading, CSS application, and image decoding. It then removes only its project, volumes, and temporary Secrets, retaining no accounts or environment for later manual login. Only the output of the current run establishes its result; it does not replace development-mode `verify:local:static-remote` Vite/HMR evidence and does not claim a browser matrix or parent specification #155 as complete.

The `EVIDENCE:` directory retains `static-remote-chromium.json` (using the corresponding channel name for Chrome/Edge), with sanitized network, rendering, and console observations and the Remote subtest's passed/failed status. Compose cleanup preserves this file. A passed subtest does not mean the entire run passed. Set `SF_BRAND_EVIDENCE_DIRECTORY` to choose the retained directory.

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

### 4. Log in through Platform Console with the initial password

After meeting the browser prerequisites above, open the [local Platform Console](https://platform.saas.forge.test/), enter the bootstrap administrator email and initial password, and select “登录” (Log in). This creates only a restricted session and should open “设置新密码” (Set a new password); platform management remains unavailable at this point.

If the initial password has expired and no regular password exists, use the restricted initial-credential reset below rather than rerunning account creation. If the UI reports an active session in the slot, follow its prompt to log out of that Platform session first.

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

1. Enter the administrator email and regular password on the login page. Successful login opens “Platform 总览” (Platform overview).
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

Because the database was reset, bootstrap the administrator again and change its initial password within 24 hours. The previous regular password no longer works. Recreate Tenants, Memberships, Quota/Plan, Subscriptions, and Tenant administrators through backend APIs; the current frontend cannot create this data.

### 4. Update the frontends and verify the deployment

The default Compose stack does not redeploy frontends or the TLS reverse proxy, whether data is retained or reset. If frontend source changed, rebuild after preparing the Console toolchain and dependencies:

```bash
(cd ../../consoles && corepack pnpm run build)
```

Publish the two new `dist` directories to their original Platform and Tenant sites, and replace each `runtime-config.json` again: every new build includes the intentionally invalid deployment template. Check HTTPS, Gateway proxying, and Password Setup routes against the browser prerequisites above. Redeployment alone does not require deleting trusted TLS certificates.

Then check:

1. Migration jobs show `Exited (0)` in `docker compose ps --all`, and backend services are ready. A running container alone does not prove API availability.
2. Close old Console tabs and reopen Platform Console. Use the existing regular password if data was retained; after a reset, use the new initial password, change it, and log in again.
3. Reload the home page to check session recovery, then log out and reload to confirm that the ended session is not restored.
4. Before testing Tenant login and switching, confirm that Tenant data was retained or recreated. Old Password Setup links cannot be used after the database is reset.

The isolated Console acceptance script removes its own temporary environment. It does not redeploy the development stack, and its test accounts cannot be used to log in to that stack.
