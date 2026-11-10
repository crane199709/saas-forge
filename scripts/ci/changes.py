#!/usr/bin/env python3
"""按实际 Git 差异选择质量检查；比较失败时保留全量验证。"""

import argparse
import json
import os
import re
import subprocess
from pathlib import Path


SCOPES = {"frontend", "backend", "main-flow", "template-smoke"}
WORKFLOWS = {
    "frontend-quality.yml": "frontend",
    "backend-quality.yml": "backend",
    "main-flow-e2e.yml": "main-flow",
    "template-smoke.yml": "template-smoke",
}
INTEGRATION = {"main-flow", "template-smoke"}


def checks_for_path(path):
    """映射到消费该文件的检查，未知路径保守执行全部检查。"""
    # 通用规范随脚手架交付，属于生成工程的输入。
    if path.startswith("docs/standards/"):
        return {"backend", "frontend", "template-smoke"}
    if path.endswith(".md") or path.startswith(("docs/", ".scratch/")):
        return set()
    if path.startswith("frontend/e2e/visual/__screenshots__/"):
        return set()
    if path.startswith(".github/workflows/"):
        scope = WORKFLOWS.get(path.removeprefix(".github/workflows/"))
        return {scope} if scope else SCOPES.copy()
    if path.startswith((".github/actions/", "scripts/ci/")):
        return SCOPES.copy()
    if path.startswith("backend/"):
        # archetype 中嵌入的 src/test 是生成工程的一部分，模板冒烟需要执行它。
        tests_only = not path.startswith("backend/forge-service-archetype/src/main/") and (
            "/src/test/" in path or "/src/it/" in path
        )
        return {"backend"} if tests_only else {"backend"} | INTEGRATION
    if path.startswith("frontend/"):
        if path.startswith("frontend/e2e/main-flow/"):
            name = Path(path).name
            if name == "main-flow.spec.ts":
                return {"frontend", "main-flow"}
            if name == "template-smoke.spec.ts":
                return {"frontend", "template-smoke"}
            if name == "notification-flow.spec.ts":
                return {"frontend"}
            return {"frontend"} | INTEGRATION
        if path.startswith("frontend/e2e/") or path in {
            "frontend/playwright.config.ts",
            "frontend/playwright.visual.config.ts",
            "frontend/playwright.notification.config.ts",
            "frontend/tsconfig.tools.json",
        }:
            return {"frontend"}
        # 真实主链路与模板冒烟只启动 tenant-app 和业务 Sub-App。
        if path.startswith("frontend/apps/platform-console/") and not path.endswith("package.json"):
            return {"frontend"}
        if path.endswith((".test.ts", ".spec.ts")) and not path.startswith(
            "frontend/packages/sub-app-kit/templates/"
        ):
            return {"frontend"}
        return {"frontend"} | INTEGRATION
    if path.startswith(("deploy/template-smoke/", "scripts/release/")):
        return {"template-smoke"}
    # 服务集成测试也直接读取部署目录中的数据库初始化脚本和 Nacos 配置。
    if path.startswith(("deploy/postgres/", "deploy/nacos/")):
        return {"backend"} | INTEGRATION
    if path.startswith("deploy/"):
        return INTEGRATION.copy()
    return SCOPES.copy()


def select_checks(event_name, ref, event, repository):
    """PR 比较共同祖先到 head；push 比较整个推送的 before 到 after。"""
    if event_name not in {"pull_request", "push"} or ref.startswith("refs/tags/"):
        return SCOPES.copy(), "手动、发布或其他调用：完整验证"
    if event_name == "pull_request":
        pull_request = event.get("pull_request", {})
        base = pull_request.get("base", {}).get("sha", "")
        head = pull_request.get("head", {}).get("sha", "")
        revisions = [f"{base}...{head}"]
    else:
        base, head = event.get("before", ""), event.get("after", "")
        revisions = [base, head]
    if not all(re.fullmatch(r"[0-9a-fA-F]{40,64}", sha) and int(sha, 16) for sha in (base, head)):
        return SCOPES.copy(), "缺少有效比较提交（含新分支）：完整验证"
    try:
        changed = subprocess.check_output(
            ["git", "diff", "--name-only", "--no-renames", "-z", *revisions, "--"],
            cwd=repository,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError):
        return SCOPES.copy(), "无法比较 Git 提交（如历史不可达）：完整验证"
    # 禁用重命名检测，让旧路径和新路径都参与选择；NUL 分隔兼容空格及换行。
    paths = [os.fsdecode(path) for path in changed.split(b"\0") if path]
    selected = set().union(*(checks_for_path(path) for path in paths))
    return selected, f"{event_name}：根据 {len(paths)} 个变更路径选择检查"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=sorted(SCOPES), required=True)
    args = parser.parse_args()
    if os.environ.get("CI_FORCE_FULL") == "true":
        selected, reason = SCOPES.copy(), "可复用工作流要求完整验证"
    else:
        try:
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        except (KeyError, OSError, ValueError):
            selected, reason = SCOPES.copy(), "事件内容不可用：完整验证"
        else:
            selected, reason = select_checks(
                os.environ.get("GITHUB_EVENT_NAME", ""),
                os.environ.get("GITHUB_REF", ""),
                event,
                os.environ.get("GITHUB_WORKSPACE", "."),
            )
    should_run = str(args.scope in selected).lower()
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"run={should_run}\n")
    summary = f"### CI 范围：{args.scope}\n\n{reason}\n\n执行重检查：`{should_run}`\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(summary)


if __name__ == "__main__":
    main()
