#!/usr/bin/env python3
"""Secure wrapper for TeamTalk's reusable 150-page document UI fixture."""

from __future__ import annotations

import argparse
import getpass
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
from dataclasses import dataclass
from typing import Union


ACCOUNT_FILE_NAME = "account.properties"
FIXTURE_ACTION_ENV = "TK_E2E_FIXTURE_ACTION"
FIXTURE_STATE_DIR_ENV = "TK_E2E_FIXTURE_STATE_DIR"
FIXTURE_CONFIRM_TARGET_ENV = "TK_E2E_CONFIRM_TARGET"
PRIVATE_DIRECTORY_MODE = 0o700
PRIVATE_FILE_MODE = 0o600
MAX_ACCOUNT_BYTES = 4096


class FixtureToolError(RuntimeError):
    """Expected, already-redacted fixture admission failure."""


@dataclass(frozen=True, repr=False)
class AccountCredentials:
    """In-memory UI-login values whose repr never reveals either field."""

    username: str
    password: str

    def __repr__(self) -> str:
        return "AccountCredentials(username=<redacted>, password=<redacted>)"


def project_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _inside(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _require_absolute_state_path(raw: str) -> Path:
    path = Path(raw)
    if not path.is_absolute():
        raise FixtureToolError("--state-dir must be an absolute path")
    return path


def _validate_private_state_dir(path: Path) -> Path:
    try:
        info = path.lstat()
    except FileNotFoundError as failure:
        raise FixtureToolError("private fixture state directory does not exist") from failure
    if not stat.S_ISDIR(info.st_mode) or path.is_symlink():
        raise FixtureToolError("fixture state path must be a real directory")
    if stat.S_IMODE(info.st_mode) != PRIVATE_DIRECTORY_MODE:
        raise FixtureToolError("fixture state directory permissions must be exactly 0700")
    if hasattr(os, "getuid") and info.st_uid != os.getuid():
        raise FixtureToolError("fixture state directory must belong to the current user")
    resolved = path.resolve(strict=True)
    if _inside(resolved, project_root().resolve(strict=True)):
        raise FixtureToolError("fixture state directory must be outside the TeamTalk repository")
    return resolved


def _validate_private_account_file(state_dir: Path) -> Path:
    account_file = state_dir / ACCOUNT_FILE_NAME
    try:
        info = account_file.lstat()
    except FileNotFoundError as failure:
        raise FixtureToolError(f"private state directory is missing {ACCOUNT_FILE_NAME}") from failure
    if not stat.S_ISREG(info.st_mode) or account_file.is_symlink():
        raise FixtureToolError("account credential path must be a real regular file")
    if stat.S_IMODE(info.st_mode) != PRIVATE_FILE_MODE:
        raise FixtureToolError("account credential permissions must be exactly 0600")
    if hasattr(os, "getuid") and info.st_uid != os.getuid():
        raise FixtureToolError("account credential file must belong to the current user")
    if info.st_nlink != 1:
        raise FixtureToolError("hard-linked account credential files are not allowed")
    if info.st_size <= 0 or info.st_size > MAX_ACCOUNT_BYTES:
        raise FixtureToolError("account credential file has an invalid size")
    return account_file


def _parse_account_text(text: str) -> AccountCredentials:
    values: dict[str, str] = {}
    lines = text.split("\n")
    for index, raw_line in enumerate(lines):
        line = raw_line[:-1] if raw_line.endswith("\r") else raw_line
        if index == len(lines) - 1 and not line:
            continue
        if not line or "=" not in line or line.startswith("="):
            raise FixtureToolError("account credential file contains a malformed record")
        key, value = line.split("=", 1)
        if key not in {"username", "password"}:
            raise FixtureToolError("account credential file contains an unknown key")
        if key in values:
            raise FixtureToolError("account credential file contains a duplicate key")
        values[key] = value
    if set(values) != {"username", "password"}:
        raise FixtureToolError("account credential file must contain exactly username and password")
    username = values["username"]
    password = values["password"]
    if not 3 <= len(username) <= 50 or "\x00" in username or not username.strip():
        raise FixtureToolError("account credential username does not satisfy TeamTalk login rules")
    password_bytes = password.encode("utf-8")
    if not 6 <= len(password) or len(password_bytes) > 72 or not password.strip():
        raise FixtureToolError("account credential password does not satisfy TeamTalk login rules")
    return AccountCredentials(username, password)


def load_account_credentials(state_dir: Union[str, Path]) -> AccountCredentials:
    """Load the same account for Desktop/Android login without exposing it in repr or argv."""

    raw = str(state_dir)
    private_dir = _validate_private_state_dir(_require_absolute_state_path(raw))
    account_file = _validate_private_account_file(private_dir)
    try:
        descriptor = os.open(
            account_file,
            os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            before = os.fstat(descriptor)
            if (
                not stat.S_ISREG(before.st_mode)
                or stat.S_IMODE(before.st_mode) != PRIVATE_FILE_MODE
                or (hasattr(os, "getuid") and before.st_uid != os.getuid())
                or before.st_nlink != 1
                or before.st_size <= 0
                or before.st_size > MAX_ACCOUNT_BYTES
            ):
                raise FixtureToolError("account credential file changed before read")
            chunks = []
            payload_size = 0
            while payload_size <= MAX_ACCOUNT_BYTES:
                chunk = os.read(descriptor, min(4096, MAX_ACCOUNT_BYTES + 1 - payload_size))
                if not chunk:
                    break
                chunks.append(chunk)
                payload_size += len(chunk)
            payload = b"".join(chunks)
            after = os.fstat(descriptor)
            if (
                len(payload) != before.st_size
                or len(payload) > MAX_ACCOUNT_BYTES
                or (before.st_dev, before.st_ino, before.st_mode, before.st_uid, before.st_nlink,
                    before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                != (after.st_dev, after.st_ino, after.st_mode, after.st_uid, after.st_nlink,
                    after.st_size, after.st_mtime_ns, after.st_ctime_ns)
            ):
                raise FixtureToolError("account credential file changed during read")
        finally:
            os.close(descriptor)
        path_after = account_file.lstat()
        if (
            path_after.st_dev != before.st_dev
            or path_after.st_ino != before.st_ino
            or path_after.st_mode != before.st_mode
            or path_after.st_uid != before.st_uid
            or path_after.st_nlink != before.st_nlink
            or path_after.st_size != before.st_size
            or path_after.st_mtime_ns != before.st_mtime_ns
            or path_after.st_ctime_ns != before.st_ctime_ns
        ):
            raise FixtureToolError("account credential path changed during read")
        text = payload.decode("utf-8")
    except FixtureToolError:
        raise
    except OSError as failure:
        raise FixtureToolError("account credential file could not be safely read") from failure
    except UnicodeDecodeError as failure:
        raise FixtureToolError("account credential file must be UTF-8") from failure
    return _parse_account_text(text)


def _create_or_validate_state_dir(path: Path) -> Path:
    created = False
    if not os.path.lexists(path):
        parent = path.parent.resolve(strict=True)
        if not parent.is_dir():
            raise FixtureToolError("fixture state parent must be an existing directory")
        os.mkdir(path, PRIVATE_DIRECTORY_MODE)
        os.chmod(path, PRIVATE_DIRECTORY_MODE)
        created = True
    try:
        return _validate_private_state_dir(path)
    except Exception:
        if created:
            path.rmdir()
        raise


def _validate_interactive_value(label: str, value: str) -> None:
    if any(character in value for character in ("\n", "\r", "\x00")):
        raise FixtureToolError(f"{label} cannot contain a line break or NUL")


def _write_private_account(state_dir: Path, username: str, password: str) -> Path:
    _validate_interactive_value("username", username)
    _validate_interactive_value("password", password)
    credentials = _parse_account_text(f"username={username}\npassword={password}\n")
    payload = f"username={credentials.username}\npassword={credentials.password}\n".encode("utf-8")
    if len(payload) > MAX_ACCOUNT_BYTES:
        raise FixtureToolError("account credential payload exceeds its size limit")

    account_file = state_dir / ACCOUNT_FILE_NAME
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = None
    created = False
    installed = False
    try:
        descriptor = os.open(account_file, flags, PRIVATE_FILE_MODE)
        created = True
        os.fchmod(descriptor, PRIVATE_FILE_MODE)
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise FixtureToolError("failed to write private account credential file")
            view = view[written:]
        os.fsync(descriptor)
        installed = True
    except FileExistsError as failure:
        raise FixtureToolError(f"{ACCOUNT_FILE_NAME} already exists; refusing to overwrite it") from failure
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if created and not installed:
            try:
                account_file.unlink()
            except FileNotFoundError:
                pass
    directory_descriptor = os.open(state_dir, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory_descriptor)
    finally:
        os.close(directory_descriptor)
    _validate_private_account_file(state_dir)
    return account_file


def _generate_account_credentials() -> AccountCredentials:
    credentials = AccountCredentials(
        username=f"e2e-doc-{secrets.token_hex(8)}",
        password=secrets.token_urlsafe(32),
    )
    # Keep generated values behind the same parser and protocol limits as manually entered values.
    return _parse_account_text(
        f"username={credentials.username}\npassword={credentials.password}\n",
    )


def initialize_account(args: argparse.Namespace) -> int:
    if not args.generate and not sys.stdin.isatty():
        raise FixtureToolError("interactive init-account requires a terminal; use --generate for automation")
    state_path = _require_absolute_state_path(args.state_dir)
    state_dir = _create_or_validate_state_dir(state_path)
    if args.generate:
        credentials = _generate_account_credentials()
    else:
        username = input("TeamTalk test username: ")
        password = getpass.getpass("TeamTalk test password: ")
        confirmation = getpass.getpass("Repeat password: ")
        if password != confirmation:
            raise FixtureToolError("password confirmation does not match")
        credentials = AccountCredentials(username, password)
    account_file = _write_private_account(
        state_dir,
        credentials.username,
        credentials.password,
    )
    print(f"Private account file created: {account_file}")
    return 0


def run_fixture(args: argparse.Namespace) -> int:
    state_dir = _validate_private_state_dir(_require_absolute_state_path(args.state_dir))
    # Validate all secret boundaries locally before Gradle starts. The Kotlin main repeats the
    # owner/mode/link checks and parses the file independently before opening a TCP connection.
    load_account_credentials(state_dir)
    if not args.confirm_target or any(character.isspace() for character in args.confirm_target):
        raise FixtureToolError("--confirm-target must be one exact host:port value")

    environment = os.environ.copy()
    environment[FIXTURE_ACTION_ENV] = args.action
    environment[FIXTURE_STATE_DIR_ENV] = str(state_dir)
    environment[FIXTURE_CONFIRM_TARGET_ENV] = args.confirm_target
    command = [
        str(project_root() / "gradlew"),
        ":server:server:documentFixture",
        "--no-daemon",
        "--no-watch-fs",
        "--max-workers=2",
    ]
    completed = subprocess.run(command, cwd=project_root(), env=environment, check=False)
    if completed.returncode != 0:
        raise FixtureToolError(f"document fixture task failed with exit code {completed.returncode}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Seed/archive TeamTalk's 150-page real UI document fixture without argv secrets.",
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    initialize = subcommands.add_parser(
        "init-account",
        help="create a 0600 account file in a repo-external 0700 state directory",
    )
    initialize.add_argument("--state-dir", required=True, help="absolute private state directory")
    initialize.add_argument(
        "--generate",
        action="store_true",
        help="generate compliant random credentials without printing their values",
    )
    initialize.set_defaults(handler=initialize_account)

    for action in ("seed", "archive"):
        command = subcommands.add_parser(action, help=f"{action} the deterministic document fixture")
        command.add_argument("--state-dir", required=True, help="absolute private state directory")
        command.add_argument(
            "--confirm-target",
            required=True,
            help="exact configured TCP target, for example im.virjar.com:5100",
        )
        command.set_defaults(handler=run_fixture, action=action)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return args.handler(args)
    except FixtureToolError as failure:
        print(f"document fixture error: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
