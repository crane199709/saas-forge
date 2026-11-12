#!/usr/bin/env python3
"""从 CI 比较的 Git 基线提取上一版权限目录。"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG = "docs/contracts/permission-catalog.json"
EMPTY = b'{"schemaVersion": 1, "permissions": []}\n'


def git(*arguments):
    return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="基线目录的临时输出位置")
    args = parser.parse_args()
    try:
        event_name = os.environ.get("GITHUB_EVENT_NAME")
        branch_push = event_name == "push" and not os.environ.get("GITHUB_REF", "").startswith("refs/tags/")
        if event_name == "pull_request" or branch_push:
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
            baseline = event["pull_request"]["base"]["sha"] if event_name == "pull_request" else event["before"]
            if not isinstance(baseline, str) or not baseline.strip():
                raise ValueError("Missing Git baseline commit")
            if event_name == "push" and baseline == "0" * 40:
                baseline = None
        else:
            parents = git("rev-list", "--parents", "-n", "1", "HEAD").decode().split()
            baseline = parents[1] if len(parents) > 1 else None
        content = EMPTY
        if baseline is not None:
            commit = git("rev-parse", "--verify", f"{baseline}^{{commit}}").decode().strip()
            # 先确认提交可达；只有目录文件尚不存在才代表空历史。
            if git("ls-tree", commit, "--", CATALOG):
                content = git("show", f"{commit}:{CATALOG}")
        args.output.write_bytes(content)
    except subprocess.CalledProcessError as error:
        print(f"Permission catalog Git baseline: {error.stderr.decode().strip()}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Permission catalog Git baseline: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
