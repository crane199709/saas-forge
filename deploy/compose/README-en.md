# Local runtime environment

[简体中文](README.md)

This directory owns PostgreSQL, Redis, Kafka, Mailpit, OTel Collector, Nacos, Nacos initialization and the shared HTTPS entry points. Applications have independent Compose lifecycles. Everyday development still uses IDE Run/Debug and `pnpm run dev`.

## Start infrastructure

```bash
cd deploy/compose
test -f .env || cp .env.example .env
# Fill in actual settings without overwriting an existing .env.
docker compose config --quiet
docker compose up -d --wait postgres redis kafka mailpit otel-collector nacos
docker compose run --rm nacos-init
```

On a new volume, PostgreSQL bootstrap creates four databases and their migrator/app accounts; service schema migrations belong to applications. Nacos initialization creates identities, permissions and configuration. Repeating it updates the declared identity passwords, so verify settings first.

The default project remains `compose`, preserving `compose_postgres-data`, `compose_redis-data`, `compose_kafka-data` and the `compose_default` network. For a custom environment, set `SF_ENVIRONMENT_PROJECT` and use the same `SF_DOCKER_NETWORK` in every application. Do not give all applications the same `COMPOSE_PROJECT_NAME`.

## Application ownership and lifecycle

| Application | Compose directory |
| --- | --- |
| Gateway | `gateway/` |
| IAM | `saas-forge-services/iam-service/` |
| Tenant Access | `saas-forge-services/tenant-access-service/` |
| Entitlement | `saas-forge-services/entitlement-service/` |
| Audit | `saas-forge-services/audit-service/` |

Copy and fill in `.env.example` in the selected directory, then run:

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps --all
# Remove only this application's containers; keep shared infrastructure.
docker compose down
```

Prepare infrastructure and service identities first. Applications use the shared Docker network for infrastructure and Nacos for service discovery. Starting an application does not start other applications. Its schema migration must succeed before the container application starts. Independent lifecycle does not guarantee business operations succeed when downstream services are unavailable.

Console containers serve existing `dist` artifacts read-only. Build the selected Console first and expose its internal HTTP through the shared HTTPS entry point. On Linux, set `CONSOLE_UID` and `CONSOLE_GID` to the artifact owner's IDs. Foreground development continues with `pnpm run dev`; see [native development](../../docs/native-local-development.md).

## Credentials and one-shot tasks

Each `.env.example` lists the required directory settings. Actual `.env` and `.secrets` are ignored by Git. Passwords must match infrastructure initialization. Prefer absolute paths for `*_FILE` and certificates; applications can reference the same restricted file without copying credentials. Relative paths resolve against the owning service directory, including when imported with `extends`.

For initial IAM signing metadata, run `bash scripts/initialize-local-iam-signing-key.sh` from the repository root. It separately operates the environment and IAM Compose projects, starts PostgreSQL, migrates IAM and initializes matching signing metadata. It does not start the IAM application. See the [acceptance maintenance guide](../acceptance/README-en.md) for credential maintenance constraints.

| Owner | Task | Invocation |
| --- | --- | --- |
| Each business service | `*-migrate` | Runs before its application; also available through `run --rm <task>` |
| IAM | `iam-platform-admin-bootstrap` | `--profile bootstrap run --rm iam-platform-admin-bootstrap` |
| IAM | `iam-platform-admin-credential-reset` | `--profile credential-reset run --rm iam-platform-admin-credential-reset` |
| IAM | `iam-reserved-service-client-bootstrap` | `--profile service-client-bootstrap run --rm iam-reserved-service-client-bootstrap` |
| IAM | `iam-reserved-service-client-replacement` | `--profile service-client-replacement run --rm iam-reserved-service-client-replacement` |
| Audit | `audit-isolation-replay` | `--profile audit-isolation-replay run --rm audit-isolation-replay` |

Prefix these invocations with `docker compose`. Maintenance tasks do not run during ordinary startup. The credential generator is `saas-forge-services/iam-service/generate-service-client-secrets.sh`; pass the destination directory. Existing files are never overwritten.

## HTTPS and complete acceptance

`local-https-development.override.yaml` retains the native HTTPS Edge and no longer starts Gateway through `depends_on`. Legacy Console TLS and browser acceptance definitions were exported; see [the extraction record](../../docs/acceptance/consoles-extraction.md).

[`deploy/acceptance`](../acceptance/README-en.md) owns complete compositions and scenario overrides, reuses application definitions with `extends`, and retains cross-service startup ordering. Shared Dockerfiles are in [`deploy/docker`](../docker/). Acceptance projects own isolated networks and volumes rather than joining the daily environment.

## Transition from the combined project

Moving configuration does not operate existing containers, volumes or private configuration. Before switching, the developer stops application containers in the old project and starts the independent projects, avoiding port and Nacos instance conflicts. Target old applications using `docker compose ... stop <application>` with the old project name, complete acceptance configuration and old environment file. Do not use `--remove-orphans` to clean up applications still in use.

Keep the original infrastructure project name; if customized, configure `SF_ENVIRONMENT_PROJECT` and verify network and volume names. Split the old `.env` according to each template and convert relative credential paths to absolute paths referencing the original files. Do not regenerate credentials. Existing `deploy/compose/.secrets` files remain usable.

Stop connected applications before shutting down infrastructure. `docker compose down` retains volumes; `down --volumes` deletes database and message data and is not part of this reorganization.

## Configuration checks

Run `python3 scripts/validate-compose-layout.py` from the repository root. It uses placeholders without reading private `.env` files or starting containers, checks project isolation, migration gates, paths and all acceptance scenarios. Existing acceptance scripts and CI remain responsible for complete integration verification.
