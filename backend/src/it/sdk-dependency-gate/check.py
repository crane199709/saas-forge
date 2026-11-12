#!/usr/bin/env python3
"""通过当前源码父 POM 的真实 Maven 构建验证 SDK 依赖门禁。"""

import argparse
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


BACKEND = Path(__file__).resolve().parents[3]
LOGS = BACKEND / "target/sdk-dependency-gate"
MIDDLEWARE = "enforce-middleware-boundary"
ALLOWLIST = "enforce-sdk-dependency-allowlist"
CLIENTS = (
    "org.springframework.data:spring-data-redis",
    "org.redisson:redisson",
    "io.lettuce:lettuce-core",
    "redis.clients:jedis",
    "org.apache.rocketmq:rocketmq-client-java",
    "com.alibaba.nacos:nacos-client",
    "com.xuxueli:xxl-job-core",
)
STARTERS = (
    "spring-boot-starter-data-redis",
    "forge-sdk-tenant / forge-sdk-security",
    "forge-sdk-mq",
    "Spring Cloud Nacos starters",
    "forge-sdk-scheduling",
)


@dataclass(frozen=True)
class Case:
    name: str
    module: str
    properties: tuple = ()
    failure: str = ""
    dependencies: tuple = ()
    hints: tuple = ()
    sdk: bool = False
    exempt: bool = False


CASES = (
    Case("business-starters", "business-positive"),
    Case("middleware-direct", "business-negative", failure=MIDDLEWARE,
         dependencies=CLIENTS, hints=STARTERS),
    Case("middleware-optional", "business-negative", ("fixture.optional=true",),
         MIDDLEWARE, CLIENTS, STARTERS),
    Case("middleware-test", "business-negative", ("fixture.scope=test",),
         MIDDLEWARE, CLIENTS, STARTERS),
    Case("middleware-exempt", "business-negative", ("forge.middleware.skip=true",),
         exempt=True),
    Case("sdk-allowed-and-transitive", "sdk-positive", sdk=True),
    Case("sdk-outside-allowlist", "sdk-negative", failure=ALLOWLIST,
         dependencies=("org.apache.commons:commons-lang3",),
         hints=("白名单外", "更新本模块 POM 的 forge.sdk.allowedDependencies"), sdk=True),
    Case("sdk-missing-allowlist", "sdk-negative", ("forge.sdk.allowedDependencies=",),
         ALLOWLIST, ("org.apache.commons:commons-lang3",), ("forge.sdk.allowedDependencies",),
         sdk=True),
    Case("sdk-optional", "sdk-negative", ("fixture.optional=true",),
         ALLOWLIST, ("org.apache.commons:commons-lang3",), ("forge.sdk.allowedDependencies",),
         sdk=True),
    Case("sdk-test", "sdk-negative", ("fixture.scope=test",),
         ALLOWLIST, ("org.apache.commons:commons-lang3",), ("forge.sdk.allowedDependencies",),
         sdk=True),
    Case("sdk-provided", "sdk-negative", ("fixture.scope=provided",),
         ALLOWLIST, ("org.apache.commons:commons-lang3",), ("forge.sdk.allowedDependencies",),
         sdk=True),
    Case("sdk-non-sdk-service", "sdk-service-negative", failure=ALLOWLIST,
         dependencies=("io.saasforge:forge-platform",), hints=("forge.sdk.allowedDependencies",),
         sdk=True),
    Case("sdk-exempt-allowed", "sdk-exempt-positive", sdk=True, exempt=True),
    Case("sdk-exempt-still-enforces-allowlist", "sdk-exempt-negative", failure=ALLOWLIST,
         dependencies=("org.apache.rocketmq:rocketmq-client-java",),
         hints=("forge.sdk.allowedDependencies",), sdk=True, exempt=True),
)


def require(condition, message, output):
    if not condition:
        raise RuntimeError(f"{message}\n\n{output}")


def maven(name, arguments, offline):
    command = [str(BACKEND / "mvnw"), "--batch-mode", "--no-transfer-progress",
               "-Dstyle.color=never"]
    if offline:
        command.append("--offline")
    result = subprocess.run(command + arguments, cwd=BACKEND, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    LOGS.mkdir(parents=True, exist_ok=True)
    log = LOGS / f"{name}.log"
    log.write_text(result.stdout)
    return result, log


def check_execution(output, module, gate, skipped=False):
    # 成功退出本身不能证明规则运行过；检查对应 execution 的 Maven 输出。
    block = re.search(
        rf"--- [^\n]*\({gate}\) @ {module} ---\n(.*?)(?=\[INFO\] ---|\[INFO\] BUILD|\Z)",
        output, re.S,
    )
    require(block is not None, f"{module}: 缺少 {gate} execution", output)
    marker = "Skipping Rule Enforcement" if skipped else "BannedDependencies passed"
    require(marker in block.group(1), f"{module}: {gate} 未{'跳过' if skipped else '通过'}", output)


def check_default_reactor(offline):
    result, log = maven("default-reactor", ["-pl", ":sdk-gate-business-positive", "validate"], offline)
    require(result.returncode != 0 and
            "Could not find the selected project in the reactor: :sdk-gate-business-positive" in result.stdout,
            "fixture 不应出现在默认 reactor 中", result.stdout)
    print(f"PASS default-reactor: fixture 未进入默认构建；{log}", flush=True)


def check_case(case, offline):
    module = f"sdk-gate-{case.module}"
    arguments = ["-P", "sdk-dependency-gate", "-pl", f":{module}"]
    arguments += [f"-D{value}" for value in case.properties]
    result, log = maven(case.name, arguments + ["validate"], offline)
    output = result.stdout
    if case.failure:
        require(result.returncode != 0, f"{case.name}: 违规依赖应导致构建失败；{log}", output)
        if case.failure == ALLOWLIST:
            require("更新本模块 POM 的 forge.sdk.allowedDependencies" in output,
                    f"{case.name}: 缺少更新白名单提示；{log}", output)
        for marker in (f"({case.failure}) on project {module}:",
                       "org.apache.maven.enforcer.rules.dependency.BannedDependencies",
                       "banned via the exclude/include list", *case.hints):
            require(marker in output, f"{case.name}: 缺少门禁错误 {marker}；{log}", output)
        for dependency in case.dependencies:
            require(f"{dependency}:" in output,
                    f"{case.name}: 错误未指出 {dependency}；{log}", output)
    else:
        require(result.returncode == 0, f"{case.name}: 应构建成功；{log}", output)
        if case.sdk:
            check_execution(output, module, ALLOWLIST)
    if case.failure != MIDDLEWARE:
        check_execution(output, module, MIDDLEWARE, skipped=case.exempt)
    print(f"PASS {case.name}: {'门禁拒绝违规依赖' if case.failure else 'BUILD SUCCESS'}；{log}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="本地已缓存依赖时使用 Maven 离线模式")
    parser.add_argument("--case", action="append", choices=["default-reactor", *(case.name for case in CASES)],
                        help="只执行指定样例，可重复传入")
    args = parser.parse_args()
    try:
        if not args.case or "default-reactor" in args.case:
            check_default_reactor(args.offline)
        for case in CASES:
            if not args.case or case.name in args.case:
                check_case(case, args.offline)
    except RuntimeError as error:
        parser.exit(1, f"FAIL {error}\n")


if __name__ == "__main__":
    main()
