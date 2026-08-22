#!/usr/bin/env python3
"""Create a git feature branch derived from the feature description.

Wraps create_new_feature.py --dry-run --json to compute the branch name,
then creates and switches to that branch in git.

Usage: git_branch.py [--json] [--short-name <name>] <feature_description>
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

# Allow both `python git_branch.py` and `from scripts.python import git_branch`
_SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(_SCRIPT_DIR))

from common import get_repo_root  # noqa: E402


def _json_line(payload: object) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"


def main(argv: list[str] | None = None) -> int:
    args = list(argv if argv is not None else sys.argv[1:])

    json_mode = "--json" in args

    repo_root = get_repo_root(Path(__file__))
    script = _SCRIPT_DIR / "create_new_feature.py"

    # Compute branch name via dry-run (no filesystem changes)
    dry_run_args = [sys.executable, str(script), "--dry-run", "--json"] + [
        a for a in args if a != "--json"
    ]
    result = subprocess.run(
        dry_run_args,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        return result.returncode

    try:
        data = json.loads(result.stdout.strip())
    except json.JSONDecodeError as exc:
        print(f"Error: failed to parse branch name output: {exc}", file=sys.stderr)
        return 1

    branch_name: str = data["BRANCH_NAME"]
    feature_num: str = data["FEATURE_NUM"]

    # Create (or switch to) the branch
    create = subprocess.run(
        ["git", "switch", "-c", branch_name],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if create.returncode != 0:
        # Branch already exists — switch to it
        switch = subprocess.run(
            ["git", "switch", branch_name],
            cwd=repo_root,
            capture_output=True,
            text=True,
        )
        if switch.returncode != 0:
            print(f"Error: {create.stderr.strip()}", file=sys.stderr)
            print(f"       {switch.stderr.strip()}", file=sys.stderr)
            return 1
        print(f"[specify] Switched to existing branch: {branch_name}", file=sys.stderr)
    else:
        print(f"[specify] Created and switched to branch: {branch_name}", file=sys.stderr)

    payload = {"BRANCH_NAME": branch_name, "FEATURE_NUM": feature_num}
    if json_mode:
        sys.stdout.write(_json_line(payload))
    else:
        print(f"BRANCH_NAME: {branch_name}")
        print(f"FEATURE_NUM: {feature_num}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
