import { spawn, spawnSync } from "node:child_process";
import { X509Certificate } from "node:crypto";
import { constants } from "node:fs";
import {
  access,
  chmod,
  mkdir,
  open,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";

import {
  createFrontendLifecycle,
  createFrontendOrchestration,
  frontendTarget,
  frontendTargets,
  stopUnusedEdge,
} from "./frontend-lifecycle.mjs";

const expectedNodeVersion = "24.14.1";
const expectedPnpmVersion = "11.22.0";
const certificateValiditySeconds = 24 * 60 * 60;
const certificateAuthorityName = "SaaS Forge Local Development CA";
const hostsEntry =
  "127.0.0.1 platform.saas.forge.test console.saas.forge.test api.saas.forge.test remote.saas.forge.test # SaaS Forge local HTTPS";

export const developmentHosts = Object.freeze([
  "platform.saas.forge.test",
  "console.saas.forge.test",
  "api.saas.forge.test",
  "remote.saas.forge.test",
]);

export function developmentHttpsPaths(repositoryRoot) {
  const directory = path.join(
    repositoryRoot,
    "deploy",
    "compose",
    ".secrets",
    "local-https-development",
  );
  return {
    directory,
    certificateAuthorityKey: path.join(directory, "root-ca.key"),
    certificateAuthorityCertificate: path.join(directory, "root-ca.pem"),
    serverKey: path.join(directory, "server.key"),
    serverCertificate: path.join(directory, "server.pem"),
    serverRequest: path.join(directory, "server.csr"),
    serverExtensions: path.join(directory, "server.ext"),
    apiTarget: path.join(
      repositoryRoot,
      "deploy",
      "compose",
      ".secrets",
      "local-service-replacement",
      "api-target.json",
    ),
    tenantLog: path.join(directory, "tenant-vite.log"),
    tenantPid: path.join(directory, "tenant-vite.pid"),
    platformLog: path.join(directory, "platform-vite.log"),
    platformPid: path.join(directory, "platform-vite.pid"),
    legacyViteLog: path.join(directory, "vite.log"),
    legacyVitePid: path.join(directory, "vite.pid"),
  };
}

export async function certificateCoversExpectedHosts(certificate) {
  try {
    const parsed = new X509Certificate(await readFile(certificate));
    return developmentHosts.every(
      (host) => parsed.checkHost(host, { subject: "never", wildcards: false }) === host,
    );
  } catch {
    return false;
  }
}

export async function ensureCertificateMaterial(paths) {
  await mkdir(paths.directory, { recursive: true, mode: 0o700 });
  await chmod(paths.directory, 0o700);

  const authorityIsUsable = await certificateAuthorityIsUsable(paths);
  const serverIsUsable =
    authorityIsUsable && (await serverCertificateIsUsable(paths));
  if (serverIsUsable) {
    return "reused";
  }

  if (!authorityIsUsable) {
    await removeCertificateAuthority(paths);
    run("openssl", [
      "req",
      "-x509",
      "-new",
      "-nodes",
      "-newkey",
      "rsa:3072",
      "-sha256",
      "-days",
      "3650",
      "-keyout",
      paths.certificateAuthorityKey,
      "-out",
      paths.certificateAuthorityCertificate,
      "-subj",
      `/CN=${certificateAuthorityName}`,
      "-addext",
      "basicConstraints=critical,CA:TRUE,pathlen:0",
      "-addext",
      "keyUsage=critical,keyCertSign,cRLSign",
      "-addext",
      "subjectKeyIdentifier=hash",
    ]);
    await chmod(paths.certificateAuthorityKey, 0o600);
    await chmod(paths.certificateAuthorityCertificate, 0o644);
  }

  await removeServerCertificate(paths);
  await writeFile(
    paths.serverExtensions,
    [
      "basicConstraints=critical,CA:FALSE",
      "keyUsage=critical,digitalSignature,keyEncipherment",
      "extendedKeyUsage=serverAuth",
      `subjectAltName=${developmentHosts.map((host) => `DNS:${host}`).join(",")}`,
      "subjectKeyIdentifier=hash",
      "authorityKeyIdentifier=keyid,issuer",
      "",
    ].join("\n"),
    { mode: 0o600 },
  );
  run("openssl", [
    "req",
    "-new",
    "-nodes",
    "-newkey",
    "rsa:3072",
    "-sha256",
    "-keyout",
    paths.serverKey,
    "-out",
    paths.serverRequest,
    "-subj",
    "/CN=platform.saas.forge.test",
  ]);
  run("openssl", [
    "x509",
    "-req",
    "-in",
    paths.serverRequest,
    "-CA",
    paths.certificateAuthorityCertificate,
    "-CAkey",
    paths.certificateAuthorityKey,
    "-CAcreateserial",
    "-days",
    "397",
    "-sha256",
    "-extfile",
    paths.serverExtensions,
    "-out",
    paths.serverCertificate,
  ]);
  await chmod(paths.serverKey, 0o640);
  await chmod(paths.serverCertificate, 0o644);
  return "created";
}

export function hasExpectedHosts(content) {
  const configuredHosts = new Set();
  for (const line of content.split(/\r?\n/u)) {
    const fields = line.replace(/#.*/u, "").trim().split(/\s+/u);
    if (fields[0] !== "127.0.0.1") continue;
    for (const host of fields.slice(1)) configuredHosts.add(host);
  }
  return developmentHosts.every((host) => configuredHosts.has(host));
}

export function viteDevelopmentCommand(consoleRoot, target = "platform") {
  const configuration = frontendTarget(target);
  return {
    command: "mise",
    args: [
      "exec",
      `node@${expectedNodeVersion}`,
      "--",
      "corepack",
      "pnpm",
      "--filter",
      configuration.package,
      "run",
      "dev",
      "--",
      "--host",
      "127.0.0.1",
      "--port",
      String(configuration.port),
      "--strictPort",
    ],
    cwd: consoleRoot,
  };
}

async function certificateAuthorityIsUsable(paths) {
  if (
    !(await filesExist(
      paths.certificateAuthorityKey,
      paths.certificateAuthorityCertificate,
    ))
  )
    return false;
  return certificateHasRemainingValidity(paths.certificateAuthorityCertificate);
}

async function serverCertificateIsUsable(paths) {
  if (!(await filesExist(paths.serverKey, paths.serverCertificate)))
    return false;
  if (!(await certificateHasRemainingValidity(paths.serverCertificate)))
    return false;
  if (!(await certificateCoversExpectedHosts(paths.serverCertificate)))
    return false;
  if (
    run(
      "openssl",
      [
        "verify",
        "-CAfile",
        paths.certificateAuthorityCertificate,
        paths.serverCertificate,
      ],
      { allowFailure: true },
    ).status !== 0
  ) {
    return false;
  }
  const certificateModulus = run(
    "openssl",
    ["x509", "-noout", "-modulus", "-in", paths.serverCertificate],
    {
      allowFailure: true,
    },
  );
  const keyModulus = run(
    "openssl",
    ["rsa", "-noout", "-modulus", "-in", paths.serverKey],
    {
      allowFailure: true,
    },
  );
  return (
    certificateModulus.status === 0 &&
    keyModulus.status === 0 &&
    certificateModulus.stdout === keyModulus.stdout
  );
}

async function certificateHasRemainingValidity(certificate) {
  return (
    run(
      "openssl",
      [
        "x509",
        "-checkend",
        String(certificateValiditySeconds),
        "-noout",
        "-in",
        certificate,
      ],
      {
        allowFailure: true,
      },
    ).status === 0
  );
}

async function filesExist(...files) {
  for (const file of files) {
    try {
      await access(file, constants.R_OK);
    } catch {
      return false;
    }
  }
  return true;
}

async function removeCertificateAuthority(paths) {
  await Promise.all([
    rm(paths.certificateAuthorityKey, { force: true }),
    rm(paths.certificateAuthorityCertificate, { force: true }),
  ]);
}

async function removeServerCertificate(paths) {
  await Promise.all([
    rm(paths.serverKey, { force: true }),
    rm(paths.serverCertificate, { force: true }),
    rm(paths.serverRequest, { force: true }),
    rm(paths.serverExtensions, { force: true }),
    rm(path.join(paths.directory, "root-ca.srl"), { force: true }),
  ]);
}

function run(command, args, { allowFailure = false, cwd, env, input } = {}) {
  const result = spawnSync(command, args, {
    cwd,
    env,
    encoding: "utf8",
    input,
    stdio: ["pipe", "pipe", "pipe"],
  });
  if (result.error && !allowFailure) {
    throw new Error(`无法执行 ${command}；请安装或修复该工具后重试。`);
  }
  if (result.status !== 0 && !allowFailure) {
    throw new Error(`${command} 执行失败；请运行 doctor 查看恢复提示。`);
  }
  return {
    status: result.status ?? 1,
    stdout: result.stdout ?? "",
  };
}

export async function installHosts({
  readHosts = () => readFile("/etc/hosts", "utf8"),
  authorize = confirm,
  appendHosts = (entry) =>
    run("sudo", ["tee", "-a", "/etc/hosts"], { input: entry }),
} = {}) {
  const existing = await readHosts();
  if (hasExpectedHosts(existing)) {
    console.log("HOSTS: 已配置，未修改 /etc/hosts。");
    return;
  }
  await authorize(
    "此操作将向 /etc/hosts 添加四个仅指向 127.0.0.1 的本地域名。输入 HOSTS 以明确授权： ",
    "HOSTS",
  );
  await appendHosts(`${hostsEntry}\n`);
  console.log("HOSTS: 已添加本地域名。");
}

async function trustCertificateAuthority(paths) {
  if (process.platform !== "darwin") {
    throw new Error("本地 HTTPS 开发入口仅支持 macOS Docker Desktop。");
  }
  if (!(await filesExist(paths.certificateAuthorityCertificate))) {
    throw new Error("缺少本地 CA；请先运行 setup。");
  }
  if (isCertificateAuthorityTrusted()) {
    console.log("TRUST: 本地 CA 已在系统信任库中，未重复安装。");
    return;
  }
  await confirm(
    "此操作将把 SaaS Forge 本地开发 CA 加入 macOS System Keychain 并设为信任根。输入 TRUST_CA 以明确授权： ",
    "TRUST_CA",
  );
  run("sudo", [
    "security",
    "add-trusted-cert",
    "-d",
    "-r",
    "trustRoot",
    "-k",
    "/Library/Keychains/System.keychain",
    paths.certificateAuthorityCertificate,
  ]);
  console.log("TRUST: 本地 CA 已加入 macOS System Keychain。");
}

function isCertificateAuthorityTrusted() {
  return (
    run(
      "security",
      [
        "find-certificate",
        "-c",
        certificateAuthorityName,
        "/Library/Keychains/System.keychain",
      ],
      {
        allowFailure: true,
      },
    ).status === 0
  );
}

export async function confirm(question, expectedValue) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new Error("拒绝在非交互终端修改系统设置。请在终端中重新执行此命令。");
  }
  const readline = createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  try {
    const value = await readline.question(question);
    if (value !== expectedValue) {
      throw new Error("未收到明确授权，未修改系统设置。");
    }
  } finally {
    readline.close();
  }
}

async function doctor(repositoryRoot, paths) {
  const results = [];
  results.push(await doctorCertificate(paths));
  results.push(await doctorHosts());
  results.push(doctorTrust());
  results.push(doctorPort(paths));
  results.push(doctorDocker());
  results.push(doctorToolchain(repositoryRoot));

  let failures = 0;
  for (const result of results) {
    console.log(
      `${result.ok ? "OK" : "BLOCKED"} [${result.code}]: ${result.message}`,
    );
    if (!result.ok) {
      failures += 1;
      console.log(`恢复：${result.recovery}`);
    }
  }
  if (failures > 0) process.exitCode = 1;
}

async function doctorCertificate(paths) {
  if (
    !(await filesExist(
      paths.certificateAuthorityKey,
      paths.certificateAuthorityCertificate,
      paths.serverKey,
      paths.serverCertificate,
    ))
  ) {
    return {
      ok: false,
      code: "CERTIFICATE_MISSING",
      message: "本地 CA 或服务器证书文件缺失。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  if (
    !(await certificateHasRemainingValidity(
      paths.certificateAuthorityCertificate,
    )) ||
    !(await certificateHasRemainingValidity(paths.serverCertificate))
  ) {
    return {
      ok: false,
      code: "CERTIFICATE_EXPIRED",
      message: "本地 CA 或服务器证书已过期或将在 24 小时内过期。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  if (!(await certificateCoversExpectedHosts(paths.serverCertificate))) {
    return {
      ok: false,
      code: "CERTIFICATE_HOST_MISMATCH",
      message: "服务器证书未覆盖固定 Platform/Tenant/API/Remote Host。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  if (
    !(await certificateAuthorityIsUsable(paths)) ||
    !(await serverCertificateIsUsable(paths))
  ) {
    return {
      ok: false,
      code: "CERTIFICATE_INVALID",
      message: "本地证书链或私钥匹配校验失败。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  return {
    ok: true,
    code: "CERTIFICATE",
    message: "本地 CA 和服务器证书有效。",
  };
}

async function doctorHosts() {
  const hosts = await readFile("/etc/hosts", "utf8").catch(() => "");
  if (!hasExpectedHosts(hosts)) {
    return {
      ok: false,
      code: "HOSTS_MISSING",
      message: "Platform、Tenant、API 或 Remote Host 未在 /etc/hosts 指向 127.0.0.1。",
      recovery: "bash scripts/local-https-development.sh hosts",
    };
  }
  return {
    ok: true,
    code: "HOSTS",
    message: "四个本地域名均由 /etc/hosts 指向 127.0.0.1。",
  };
}

function doctorTrust() {
  if (process.platform !== "darwin") {
    return {
      ok: false,
      code: "PLATFORM_UNSUPPORTED",
      message: "当前系统不是受支持的 macOS Docker Desktop 环境。",
      recovery: "请在 macOS Docker Desktop 中运行该入口。",
    };
  }
  if (!isCertificateAuthorityTrusted()) {
    return {
      ok: false,
      code: "CERTIFICATE_UNTRUSTED",
      message: "本地 CA 未安装到 macOS System Keychain。",
      recovery: "bash scripts/local-https-development.sh trust-ca",
    };
  }
  return {
    ok: true,
    code: "CERTIFICATE_TRUST",
    message: "本地 CA 已安装到 macOS System Keychain。",
  };
}

function doctorPort(paths) {
  const result = run("lsof", ["-nP", "-iTCP:443", "-sTCP:LISTEN"], {
    allowFailure: true,
  });
  if (result.status === 0) {
    const edge = run(
      "curl",
      [
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout",
        "2",
        "--max-time",
        "5",
        "--cacert",
        paths.certificateAuthorityCertificate,
        "https://platform.saas.forge.test/",
      ],
      { allowFailure: true },
    );
    if (edge.status === 0) {
      return {
        ok: true,
        code: "HTTPS_PORT",
        message: "443 端口由可验证的本地 HTTPS Edge 提供。",
      };
    }
    return {
      ok: false,
      code: "PORT_CONFLICT",
      message: "127.0.0.1:443 已有监听者。",
      recovery:
        "停止占用 443 的本地服务后重试；不要同时启动验收 console-tls 入口。",
    };
  }
  return {
    ok: true,
    code: "HTTPS_PORT",
    message: "443 端口可供 Docker TLS Edge 使用。",
  };
}

function doctorDocker() {
  const result = run("docker", ["info", "--format", "{{.ServerVersion}}"], {
    allowFailure: true,
  });
  if (result.status !== 0) {
    return {
      ok: false,
      code: "DOCKER_UNAVAILABLE",
      message: "Docker Desktop 不可用。",
      recovery: "启动 Docker Desktop，然后重新运行 doctor。",
    };
  }
  return { ok: true, code: "DOCKER", message: "Docker Desktop 可用。" };
}

export function doctorToolchain(
  repositoryRoot,
  execute = run,
  target = "platform",
) {
  const consoleRoot = path.join(repositoryRoot, "consoles");
  const command = viteDevelopmentCommand(consoleRoot, target);
  const environment = {
    ...process.env,
    COREPACK_ENABLE_NETWORK: "0",
    PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE: "false",
  };
  const node = execute(
    "mise",
    ["exec", `node@${expectedNodeVersion}`, "--", "node", "--version"],
    {
      allowFailure: true,
      cwd: consoleRoot,
      env: environment,
    },
  );
  const pnpm = execute(
    "mise",
    [
      "exec",
      `node@${expectedNodeVersion}`,
      "--",
      "corepack",
      "pnpm",
      "--version",
    ],
    {
      allowFailure: true,
      cwd: command.cwd,
      env: environment,
    },
  );
  const dependencies = execute(
    "test",
    ["-d", path.join(consoleRoot, "node_modules")],
    { allowFailure: true },
  );
  const vite = execute(
    "mise",
    [
      "exec",
      `node@${expectedNodeVersion}`,
      "--",
      "corepack",
      "pnpm",
      "--filter",
      frontendTarget(target).package,
      "exec",
      "vite",
      "--version",
    ],
    {
      allowFailure: true,
      cwd: command.cwd,
      env: environment,
    },
  );
  if (
    node.status !== 0 ||
    node.stdout.trim() !== `v${expectedNodeVersion}` ||
    pnpm.status !== 0 ||
    pnpm.stdout.trim() !== expectedPnpmVersion ||
    dependencies.status !== 0 ||
    vite.status !== 0
  ) {
    return {
      ok: false,
      code: "TOOLCHAIN_INVALID",
      message: `Console 需要 Node ${expectedNodeVersion}、pnpm ${expectedPnpmVersion} 和既有依赖。`,
      recovery:
        "在 consoles/ 中显式运行 pnpm install --frozen-lockfile；doctor 与 start 不会安装依赖。",
    };
  }
  return {
    ok: true,
    code: "TOOLCHAIN",
    message: "Console Node、pnpm 和依赖目录均符合固定配置。",
  };
}

async function ensureApiTarget(targetFile) {
  try {
    await access(targetFile, constants.R_OK);
  } catch {
    await mkdir(path.dirname(targetFile), { recursive: true, mode: 0o700 });
    await chmod(path.dirname(targetFile), 0o700);
    await writeFile(targetFile, '{"hostname":"gateway","port":8080}\n', {
      mode: 0o600,
    });
  }
}

export function edgeStartArguments(repositoryRoot) {
  const composeDirectory = path.join(repositoryRoot, "deploy", "compose");
  return [
    "compose",
    "--project-directory",
    composeDirectory,
    "--file",
    path.join(composeDirectory, "compose.yaml"),
    "--file",
    path.join(composeDirectory, "local-https-development.override.yaml"),
    "up",
    "--detach",
    // Edge 是独立入口；启动依赖会重建应用拓扑，超出该恢复命令的边界。
    "--no-deps",
    "--force-recreate",
    "local-https-edge",
  ];
}

function edgeComposePrefix(repositoryRoot) {
  const composeDirectory = path.join(repositoryRoot, "deploy", "compose");
  return [
    "compose",
    "--project-directory",
    composeDirectory,
    "--file",
    path.join(composeDirectory, "compose.yaml"),
    "--file",
    path.join(composeDirectory, "local-https-development.override.yaml"),
  ];
}

function edgeEnvironment(paths) {
  return {
    ...process.env,
    SF_LOCAL_HTTPS_CERT: paths.serverCertificate,
    SF_LOCAL_HTTPS_API_TARGET_FILE: paths.apiTarget,
    SF_LOCAL_HTTPS_KEY: paths.serverKey,
    SF_LOCAL_HTTPS_HOST_GID: String(process.getgid?.() ?? os.userInfo().gid),
  };
}

function inspectProcess(pid) {
  const fields = {};
  for (const [name, format] of [
    ["pid", "pid="],
    ["processGroupId", "pgid="],
    ["startedAt", "lstart="],
    ["command", "command="],
  ]) {
    const result = run("ps", ["-p", String(pid), "-o", format], {
      allowFailure: true,
    });
    if (result.status !== 0 || result.stdout.trim().length === 0)
      return undefined;
    fields[name] = result.stdout.trim();
  }
  const cwd = run("lsof", ["-a", "-p", String(pid), "-d", "cwd", "-Fn"], {
    allowFailure: true,
  })
    .stdout.split(/\r?\n/u)
    .find((line) => line.startsWith("n"))
    ?.slice(1);
  if (cwd === undefined) return undefined;
  return {
    pid: Number.parseInt(fields.pid, 10),
    processGroupId: Number.parseInt(fields.processGroupId, 10),
    startedAt: fields.startedAt,
    command: fields.command,
    cwd,
  };
}

function inspectListener(port) {
  const result = run(
    "lsof",
    ["-nP", `-iTCP:${port}`, "-sTCP:LISTEN", "-FpFn"],
    { allowFailure: true },
  );
  if (result.status !== 0) return undefined;
  const lines = result.stdout.split(/\r?\n/u);
  const pid = Number.parseInt(
    lines.find((line) => line.startsWith("p"))?.slice(1) ?? "",
    10,
  );
  const name = lines.find((line) => line.startsWith("n"))?.slice(1);
  if (!Number.isSafeInteger(pid) || name === undefined) {
    return { address: "UNKNOWN", port, processGroupId: undefined };
  }
  const owner = inspectProcess(pid);
  const suffix = `:${port}`;
  return {
    address: name.endsWith(suffix) ? name.slice(0, -suffix.length) : name,
    port,
    processGroupId: owner?.processGroupId,
  };
}

function isHttpsReady(paths, host) {
  return (
    run(
      "curl",
      [
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout",
        "2",
        "--max-time",
        "5",
        "--cacert",
        paths.certificateAuthorityCertificate,
        `https://${host}/`,
      ],
      { allowFailure: true },
    ).status === 0
  );
}

function inspectEdge(repositoryRoot, paths, runCommand = run) {
  const composeDirectory = path.join(repositoryRoot, "deploy", "compose");
  const prefix = edgeComposePrefix(repositoryRoot);
  const env = edgeEnvironment(paths);
  const containerId = runCommand(
    "docker",
    [...prefix, "ps", "--all", "--quiet", "local-https-edge"],
    { allowFailure: true, cwd: composeDirectory, env },
  );
  if (containerId.status !== 0)
    throw new Error("无法检查共享 HTTPS Edge；请确认 Docker Desktop 可用。");
  const id = containerId.stdout.trim();
  if (id.length === 0)
    return { exists: false, running: false, compatible: false };
  // 只读取身份、端口和健康字段；完整 inspect 会包含原始环境变量与凭据。
  const format =
    '{"id":{{json .Id}},"running":{{json .State.Running}},"health":{{with (index .State "Health")}}{{json .Status}}{{else}}null{{end}},"ports":{{json .HostConfig.PortBindings}},"projectDirectory":{{json (index .Config.Labels "com.docker.compose.project.working_dir")}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"hash":{{json (index .Config.Labels "com.docker.compose.config-hash")}}}';
  const inspection = runCommand("docker", ["inspect", "--format", format, id], {
    allowFailure: true,
  });
  if (inspection.status !== 0)
    throw new Error("无法读取共享 HTTPS Edge 容器身份。");
  let container;
  try {
    container = JSON.parse(inspection.stdout);
  } catch {
    throw new Error("共享 HTTPS Edge 容器状态不可解析。");
  }
  const expectedHash = runCommand(
    "docker",
    [...prefix, "config", "--hash", "local-https-edge"],
    { allowFailure: true, cwd: composeDirectory, env },
  );
  const hash = expectedHash.stdout.trim().split(/\s+/u).at(-1);
  const managed =
    container.projectDirectory === composeDirectory &&
    container.service === "local-https-edge";
  const configurationMatches =
    managed &&
    expectedHash.status === 0 &&
    container.hash === hash &&
    container.ports?.["8443/tcp"]?.some(
      (binding) => binding.HostIp === "127.0.0.1" && binding.HostPort === "443",
    ) === true;
  const healthy = container.health == null || container.health === "healthy";
  return {
    exists: true,
    identity: container.id,
    running: container.running === true,
    managed,
    configurationMatches,
    healthy,
    compatible: configurationMatches && healthy,
  };
}

/** Edge 的公共生命周期只管理当前 Compose 项目；停止时必须携带先前捕获的容器身份。 */
export function createHttpsEdgeLifecycle({
  repositoryRoot,
  paths,
  runCommand = run,
  listener = inspectListener,
}) {
  return {
    async ensure(acquired) {
      const initial = await this.status();
      if (initial.state === "RUNNING") return "reused";
      if (initial.state !== "STOPPED")
        throw new Error("共享 Edge 身份、配置或就绪状态异常，拒绝启动。");
      const edge = inspectEdge(repositoryRoot, paths, runCommand);
      try {
        runCommand(
          "docker",
          edge.exists
            ? [
                ...edgeComposePrefix(repositoryRoot),
                "start",
                "local-https-edge",
              ]
            : edgeStartArguments(repositoryRoot),
          {
            cwd: path.join(repositoryRoot, "deploy", "compose"),
            env: edgeEnvironment(paths),
          },
        );
      } finally {
        // Docker 可能在创建或启动成功后返回失败，仍需登记本次获得的资源供回滚。
        const current = inspectEdge(repositoryRoot, paths, runCommand);
        if (
          current.exists &&
          current.managed &&
          current.configurationMatches &&
          (!edge.exists || current.identity === edge.identity)
        )
          acquired(current.identity);
      }
      return "started";
    },
    async stop(identity) {
      const current = inspectEdge(repositoryRoot, paths, runCommand);
      if (!current.exists) return "already-stopped";
      if (
        identity === undefined ||
        current.identity !== identity ||
        !current.managed ||
        !current.configurationMatches
      ) {
        throw new Error("共享 Edge 身份或配置在停止前变化，拒绝停止。");
      }
      if (!current.running) return "already-stopped";
      runCommand("docker", ["stop", identity], { cwd: repositoryRoot });
      return "stopped";
    },
    async status() {
      const edge = inspectEdge(repositoryRoot, paths, runCommand);
      const listening = listener(443) !== undefined;
      let state;
      if ((!edge.exists && listening) || (edge.exists && !edge.managed))
        state = "UNMANAGED";
      else if (edge.exists && !edge.configurationMatches) state = "INVALID";
      else if (!edge.running) state = listening ? "UNMANAGED" : "STOPPED";
      else state = edge.healthy && listening ? "RUNNING" : "UNREADY";
      return {
        state,
        identity: edge.identity,
        port: 443,
        exitCode: state === "RUNNING" || state === "STOPPED" ? 0 : 1,
      };
    },
  };
}

function matchesManagedConsole(processInfo, record, repositoryRoot, target) {
  return (
    processInfo.pid === record.pid &&
    processInfo.processGroupId === record.pid &&
    processInfo.startedAt === record.processStartedAt &&
    processInfo.cwd === path.join(repositoryRoot, "consoles") &&
    processInfo.command.split(/\s+/u).includes(frontendTarget(target).package)
  );
}

async function readOptionalFile(file) {
  try {
    return await readFile(file, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return undefined;
    throw error;
  }
}

function createConsoleSystem(repositoryRoot, paths, target) {
  const { label, port } = frontendTarget(target);
  const pidPath = paths[`${target}Pid`];
  const logPath = paths[`${target}Log`];
  const composeDirectory = path.join(repositoryRoot, "deploy", "compose");
  let edgeCompatibility;

  function compatibleEdge() {
    const now = Date.now();
    if (
      edgeCompatibility !== undefined &&
      now - edgeCompatibility.checkedAt < 1_000
    ) {
      return edgeCompatibility.compatible;
    }
    let compatible = false;
    try {
      const edge = inspectEdge(repositoryRoot, paths);
      compatible = edge.running && edge.compatible;
    } catch {
      compatible = false;
    }
    edgeCompatibility = { checkedAt: now, compatible };
    return compatible;
  }

  return {
    now: () => Date.now(),
    readPidFile: () => readOptionalFile(pidPath),
    readLegacyPidFile: () =>
      target === "platform" ? readOptionalFile(paths.legacyVitePid) : undefined,
    inspectProcess: async (pid) => inspectProcess(pid),
    inspectListener: async (port) => inspectListener(port),
    isHttpsReady: async (host) => compatibleEdge() && isHttpsReady(paths, host),
    wait: (milliseconds) =>
      new Promise((resolve) => setTimeout(resolve, milliseconds)),
    async preflight({ allowManagedConsole }) {
      if (
        !(await certificateAuthorityIsUsable(paths)) ||
        !(await serverCertificateIsUsable(paths))
      ) {
        throw new Error("本地证书尚未就绪；请先运行 setup。");
      }
      if (!hasExpectedHosts(await readFile("/etc/hosts", "utf8"))) {
        throw new Error("本地域名尚未就绪；请先运行 hosts。");
      }
      if (!isCertificateAuthorityTrusted()) {
        throw new Error("本地 CA 尚未受信；请先运行 trust-ca。");
      }
      const toolchain = doctorToolchain(repositoryRoot, run, target);
      if (!toolchain.ok)
        throw new Error(`${toolchain.message} ${toolchain.recovery}`);
      if (doctorDocker().ok !== true) {
        throw new Error("Docker Desktop 不可用；请先启动 Docker Desktop。");
      }
      if (!allowManagedConsole && inspectListener(port) !== undefined) {
        throw new Error(`127.0.0.1:${port} 已有未知监听者，拒绝启动。`);
      }
      const listener = inspectListener(443);
      if (listener !== undefined) {
        const edge = inspectEdge(repositoryRoot, paths);
        if (!edge.running || !edge.compatible) {
          throw new Error("127.0.0.1:443 已由未知或不兼容的监听者占用。");
        }
      }
      await ensureApiTarget(paths.apiTarget);
    },
    async spawnConsole(options) {
      await mkdir(paths.directory, { recursive: true, mode: 0o700 });
      await chmod(paths.directory, 0o700);
      const log = await open(
        logPath,
        options.append ? "a" : "w",
        options.logMode,
      );
      await chmod(logPath, options.logMode);
      const command = viteDevelopmentCommand(
        path.join(repositoryRoot, "consoles"),
        target,
      );
      let child;
      try {
        child = spawn(command.command, command.args, {
          cwd: command.cwd,
          detached: true,
          env: {
            ...process.env,
            COREPACK_ENABLE_NETWORK: "0",
            PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE: "false",
          },
          stdio: ["ignore", log.fd, log.fd],
        });
        child.unref();
      } finally {
        await log.close();
      }
      for (let attempt = 0; attempt < 20; attempt += 1) {
        const processInfo = inspectProcess(child.pid);
        if (processInfo !== undefined) {
          return { pid: child.pid, processStartedAt: processInfo.startedAt };
        }
        await new Promise((resolve) => setTimeout(resolve, 50));
      }
      try {
        process.kill(-child.pid, "SIGTERM");
      } catch {
        // 进程可能已自行退出；此处不能升级为按端口终止。
      }
      throw new Error(`${label} 进程启动失败；请查看 ${logPath}。`);
    },
    async writePidFile(value, options) {
      await writeFile(pidPath, `${value}\n`, options);
      await chmod(pidPath, options.mode);
    },
    removePidFile: () => rm(pidPath, { force: true }),
    removeLegacyPidFile: () => rm(paths.legacyVitePid, { force: true }),
    async ensureEdge() {
      const listener = inspectListener(443);
      const edge = inspectEdge(repositoryRoot, paths);
      if (edge.running && edge.compatible && listener !== undefined)
        return "reused";
      if (listener !== undefined) {
        throw new Error("127.0.0.1:443 已由未知或不兼容的监听者占用。");
      }
      const env = edgeEnvironment(paths);
      if (edge.exists && edge.compatible && !edge.running) {
        run(
          "docker",
          [...edgeComposePrefix(repositoryRoot), "start", "local-https-edge"],
          {
            cwd: composeDirectory,
            env,
          },
        );
      } else {
        run("docker", edgeStartArguments(repositoryRoot), {
          cwd: composeDirectory,
          env,
        });
      }
      edgeCompatibility = { checkedAt: Date.now(), compatible: true };
      return "started";
    },
    async terminateConsole(record, signal) {
      const processInfo = inspectProcess(record.pid);
      if (
        processInfo === undefined ||
        !matchesManagedConsole(processInfo, record, repositoryRoot, target)
      ) {
        throw new Error(`${label} 进程身份在发送信号前发生变化，拒绝停止。`);
      }
      process.kill(-record.pid, signal);
    },
    async stopEdgeIfUnused() {
      return stopUnusedEdge({
        observe: (name) =>
          createFrontendLifecycle({
            repositoryRoot,
            target: name,
            system: createConsoleSystem(repositoryRoot, paths, name),
          }).run("status"),
        stop: async () => {
          const edge = createHttpsEdgeLifecycle({ repositoryRoot, paths });
          const initial = await edge.status();
          if (!["STOPPED", "RUNNING", "UNREADY"].includes(initial.state))
            throw new Error("共享 Edge 身份或配置异常，拒绝停止。");
          if (initial.state === "STOPPED") return "already-stopped";
          const result = await edge.stop(initial.identity);
          edgeCompatibility = { checkedAt: Date.now(), compatible: false };
          return result;
        },
      });
    },
  };
}

async function runConsoleLifecycle(command, repositoryRoot, paths, target) {
  if (target === "all") {
    const systems = Object.fromEntries(
      Object.keys(frontendTargets).map((name) => [
        name,
        createConsoleSystem(repositoryRoot, paths, name),
      ]),
    );
    const result = await createFrontendOrchestration({
      repositoryRoot,
      systems,
      edge: createHttpsEdgeLifecycle({ repositoryRoot, paths }),
    }).run(command);
    for (const [name, consoleState] of Object.entries(result.consoles)) {
      console.log(
        `${name.toUpperCase()}: ${consoleState.state} | 127.0.0.1:${consoleState.port} | https://${consoleState.host} | HTTPS ${consoleState.state === "RUNNING" ? "READY" : "NOT_READY"}`,
      );
    }
    console.log(
      `EDGE: ${result.edge.state} | 127.0.0.1:443 | ${result.edge.state === "RUNNING" ? "READY" : "NOT_READY"}`,
    );
    process.exitCode = result.exitCode;
    return;
  }
  const lifecycle = createFrontendLifecycle({
    target,
    repositoryRoot,
    system: createConsoleSystem(repositoryRoot, paths, target),
  });
  const result = await lifecycle.run(command);
  console.log(`${target.toUpperCase()}: ${result.state}`);
  if (command === "start" && result.state === "RUNNING") {
    console.log(`READY: https://${frontendTarget(target).host}`);
  }
  process.exitCode = result.exitCode;
}

function usage() {
  console.error(
    "用法：bash scripts/local-https-development.sh <start|status|stop> edge|platform|tenant|all\n" +
      "      bash scripts/local-https-development.sh <setup|hosts|trust-ca|doctor>",
  );
}

export function localHttpsDevelopmentCommand(arguments_) {
  if (
    arguments_.length === 1 &&
    ["setup", "hosts", "trust-ca", "doctor"].includes(arguments_[0])
  ) {
    return { command: arguments_[0] };
  }
  if (
    arguments_.length === 2 &&
    ["start", "status", "stop"].includes(arguments_[0]) &&
    (["all", "edge"].includes(arguments_[1]) ||
      Object.hasOwn(frontendTargets, arguments_[1]))
  ) {
    return { command: arguments_[0], target: arguments_[1] };
  }
  return undefined;
}

async function main(arguments_) {
  const request = localHttpsDevelopmentCommand(arguments_);
  if (request === undefined) {
    usage();
    process.exitCode = 2;
    return;
  }
  const { command } = request;
  const repositoryRoot = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "..",
  );
  const paths = developmentHttpsPaths(repositoryRoot);
  try {
    switch (command) {
      case "setup": {
        const result = await ensureCertificateMaterial(paths);
        console.log(
          `CERTIFICATE: 已${result === "created" ? "创建" : "复用"}本地 CA 与服务器证书。`,
        );
        console.log(
          "NEXT: 运行 hosts 和 trust-ca；两者都会在修改系统前请求明确授权。",
        );
        break;
      }
      case "hosts":
        await installHosts();
        break;
      case "trust-ca":
        await trustCertificateAuthority(paths);
        break;
      case "doctor":
        await doctor(repositoryRoot, paths);
        break;
      case "start":
      case "status":
      case "stop":
        if (request.target === "edge") {
          const edge = createHttpsEdgeLifecycle({ repositoryRoot, paths });
          if (command === "start") {
            // Edge 只依赖环境准备，不检查或启动 Vite，也不需要生成 API Client。
            for (const check of [
              await doctorCertificate(paths),
              await doctorHosts(),
              doctorTrust(),
              doctorDocker(),
            ]) {
              if (!check.ok)
                throw new Error(`${check.message} ${check.recovery}`);
            }
            await ensureApiTarget(paths.apiTarget);
            await edge.ensure(() => {});
          } else if (command === "stop") {
            const initial = await edge.status();
            await edge.stop(initial.identity);
          }
          const status = await edge.status();
          console.log(`EDGE: ${status.state} | 127.0.0.1:443`);
          process.exitCode = status.exitCode;
          break;
        }
        await runConsoleLifecycle(
          command,
          repositoryRoot,
          paths,
          request.target,
        );
        break;
      default:
        throw new Error("不支持的命令。");
    }
  } catch (error) {
    console.error(
      `BLOCKED: ${error instanceof Error ? error.message : "本地 HTTPS 开发入口失败。"}`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
