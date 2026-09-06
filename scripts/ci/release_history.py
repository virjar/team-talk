#!/usr/bin/env python3
"""Resolve CI's comparison commit without losing protocol history reserved by release tags.

Run after checkout with fetch-depth: 0. Stdout contains only the optional releaseBase SHA;
Gradle remains responsible for version changes and all release/bundle validation.
"""

import re
import subprocess
import sys
from pathlib import Path


FROZEN_PATHS = ("protocol/protocol/releases", "protocol/protocol/contracts")


def git(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(
        ["git", *arguments], cwd=root, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or f"Git failed: {arguments[0]}")
    return result


def comparison_base(root: Path, requested: str) -> str:
    if not requested or requested == "0" * 40:
        return ""
    if not re.fullmatch(r"[0-9a-fA-F]{40}", requested):
        raise ValueError("The CI comparison base must be a full 40-digit Git commit SHA")
    requested = requested.lower()
    if git(root, "cat-file", "-e", requested + "^{commit}", check=False).returncode:
        # A force-pushed former tip may no longer be reachable through origin's current branches.
        git(root, "fetch", "--no-tags", "--no-recurse-submodules", "origin", requested)
        git(root, "cat-file", "-e", requested + "^{commit}")
    ancestor = git(root, "merge-base", "--is-ancestor", requested, "HEAD", check=False)
    if ancestor.returncode == 0:
        return requested
    if ancestor.returncode != 1:
        raise RuntimeError(ancestor.stderr.strip() or "Cannot inspect CI comparison ancestry")
    bases = git(root, "merge-base", "--all", requested, "HEAD", check=False).stdout.splitlines()
    if len(bases) != 1:
        raise RuntimeError("Rewritten history needs exactly one common ancestor; refusing to skip release history checks")
    return bases[0]


def verify_tagged_protocol_history(root: Path) -> None:
    # Include tags outside HEAD's ancestry: rewriting a branch must not discard published contracts.
    tags = git(root, "tag", "--list").stdout.splitlines()
    for tag in tags:
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
            continue
        changed = git(
            root, "diff", "--name-only", "--no-renames", "--diff-filter=DMT",
            f"refs/tags/{tag}^{{commit}}", "HEAD", "--", *FROZEN_PATHS,
        ).stdout.strip()
        if changed:
            raise RuntimeError(f"Frozen protocol records from {tag} were changed or removed:\n{changed}")


def resolve(root: Path, requested: str) -> str:
    if git(root, "rev-parse", "--is-shallow-repository").stdout.strip() != "false":
        raise RuntimeError("Release history checks require checkout with fetch-depth: 0")
    base = comparison_base(root, requested)
    verify_tagged_protocol_history(root)
    return base


if __name__ == "__main__":
    try:
        if len(sys.argv) > 2:
            raise ValueError("Usage: release_history.py [before-or-base-sha]")
        print(resolve(Path.cwd(), sys.argv[1] if len(sys.argv) == 2 else ""))
    except (ValueError, RuntimeError) as failure:
        print(f"Release history check failed: {failure}", file=sys.stderr)
        sys.exit(1)
