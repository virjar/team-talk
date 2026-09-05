"""Security regression tests for the document fixture command-line wrapper."""

from __future__ import annotations

import argparse
import contextlib
import io
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts.e2e import document_fixture as fixture


class DocumentFixtureSecurityTest(unittest.TestCase):
    GENERATED_TOKEN = "0123456789abcdef"
    GENERATED_USERNAME = f"e2e-doc-{GENERATED_TOKEN}"
    GENERATED_PASSWORD = "generated-test-password-with-sufficient-entropy"
    EXISTING_USERNAME = "existing-fixture-user"
    EXISTING_PASSWORD = "existing-fixture-password"

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory(
            prefix="teamtalk-document-fixture-test-",
        )
        self.addCleanup(self.temporary_directory.cleanup)
        self.temporary_root = Path(self.temporary_directory.name)

    def _state_dir(self, name: str = "state") -> Path:
        state_dir = self.temporary_root / name
        state_dir.mkdir(mode=fixture.PRIVATE_DIRECTORY_MODE)
        state_dir.chmod(fixture.PRIVATE_DIRECTORY_MODE)
        return state_dir

    def _write_account(
        self,
        state_dir: Path,
        username: str = EXISTING_USERNAME,
        password: str = EXISTING_PASSWORD,
    ) -> Path:
        account_file = state_dir / fixture.ACCOUNT_FILE_NAME
        account_file.write_text(
            f"username={username}\npassword={password}\n",
            encoding="utf-8",
        )
        account_file.chmod(fixture.PRIVATE_FILE_MODE)
        return account_file

    def _generated_account_patches(self):
        return (
            mock.patch.object(fixture.secrets, "token_hex", return_value=self.GENERATED_TOKEN),
            mock.patch.object(
                fixture.secrets,
                "token_urlsafe",
                return_value=self.GENERATED_PASSWORD,
            ),
        )

    def test_generate_creates_private_state_without_disclosing_credentials(self) -> None:
        state_dir = self.temporary_root / "generated-state"
        stdout = io.StringIO()
        stderr = io.StringIO()
        token_patch, password_patch = self._generated_account_patches()

        with token_patch, password_patch, contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = fixture.initialize_account(
                argparse.Namespace(generate=True, state_dir=str(state_dir)),
            )

        self.assertEqual(0, result)
        account_file = state_dir / fixture.ACCOUNT_FILE_NAME
        self.assertEqual(0o700, stat.S_IMODE(state_dir.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(account_file.stat().st_mode))
        credentials = fixture.load_account_credentials(state_dir)
        self.assertEqual(self.GENERATED_USERNAME, credentials.username)
        self.assertEqual(self.GENERATED_PASSWORD, credentials.password)

        observable_text = stdout.getvalue() + stderr.getvalue() + repr(credentials)
        self.assertFalse(self.GENERATED_USERNAME in observable_text)
        self.assertFalse(self.GENERATED_PASSWORD in observable_text)
        self.assertEqual(
            "AccountCredentials(username=<redacted>, password=<redacted>)",
            repr(credentials),
        )

    def test_generate_refuses_to_overwrite_existing_account(self) -> None:
        state_dir = self._state_dir()
        account_file = self._write_account(state_dir)
        original_payload = account_file.read_bytes()
        token_patch, password_patch = self._generated_account_patches()

        with token_patch, password_patch, self.assertRaises(fixture.FixtureToolError) as failure:
            fixture.initialize_account(
                argparse.Namespace(generate=True, state_dir=str(state_dir)),
            )

        self.assertIn("refusing to overwrite", str(failure.exception))
        self.assertEqual(original_payload, account_file.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(account_file.stat().st_mode))

    def test_load_rejects_symbolic_link_account(self) -> None:
        state_dir = self._state_dir()
        target = self.temporary_root / "symlink-target.properties"
        target.write_text(
            f"username={self.EXISTING_USERNAME}\npassword={self.EXISTING_PASSWORD}\n",
            encoding="utf-8",
        )
        target.chmod(fixture.PRIVATE_FILE_MODE)
        (state_dir / fixture.ACCOUNT_FILE_NAME).symlink_to(target)

        with self.assertRaises(fixture.FixtureToolError) as failure:
            fixture.load_account_credentials(state_dir)

        self.assertIn("real regular file", str(failure.exception))

    def test_load_rejects_hard_linked_account(self) -> None:
        state_dir = self._state_dir()
        target = self.temporary_root / "hard-link-target.properties"
        target.write_text(
            f"username={self.EXISTING_USERNAME}\npassword={self.EXISTING_PASSWORD}\n",
            encoding="utf-8",
        )
        target.chmod(fixture.PRIVATE_FILE_MODE)
        os.link(target, state_dir / fixture.ACCOUNT_FILE_NAME)

        with self.assertRaises(fixture.FixtureToolError) as failure:
            fixture.load_account_credentials(state_dir)

        self.assertIn("hard-linked", str(failure.exception))

    def test_load_rejects_wide_account_permissions(self) -> None:
        state_dir = self._state_dir()
        account_file = self._write_account(state_dir)
        account_file.chmod(0o640)

        with self.assertRaises(fixture.FixtureToolError) as failure:
            fixture.load_account_credentials(state_dir)

        self.assertIn("exactly 0600", str(failure.exception))

    def test_load_detects_account_path_replacement_during_read(self) -> None:
        state_dir = self._state_dir()
        account_file = self._write_account(state_dir)
        replacement = state_dir / "replacement.properties"
        replacement.write_text(
            "username=replacement-user\npassword=replacement-password\n",
            encoding="utf-8",
        )
        replacement.chmod(fixture.PRIVATE_FILE_MODE)
        real_read = os.read
        replacement_performed = False

        def replace_path_before_read(descriptor: int, count: int) -> bytes:
            nonlocal replacement_performed
            if not replacement_performed:
                os.replace(replacement, account_file)
                replacement_performed = True
            return real_read(descriptor, count)

        with mock.patch.object(fixture.os, "read", side_effect=replace_path_before_read):
            with self.assertRaises(fixture.FixtureToolError) as failure:
                fixture.load_account_credentials(state_dir)

        self.assertTrue(replacement_performed)
        self.assertIn("changed", str(failure.exception))

    def test_run_fixture_keeps_credentials_out_of_argv_and_environment(self) -> None:
        state_dir = self._state_dir()
        self._write_account(state_dir)
        project_directory = self.temporary_root / "project"
        project_directory.mkdir()
        completed = subprocess.CompletedProcess(args=[], returncode=0)
        arguments = argparse.Namespace(
            state_dir=str(state_dir),
            confirm_target="fixture.example:5100",
            action="seed",
        )

        with mock.patch.object(fixture, "project_root", return_value=project_directory), mock.patch.dict(
            fixture.os.environ,
            {"DOCUMENT_FIXTURE_TEST_AMBIENT": "preserved"},
            clear=True,
        ), mock.patch.object(fixture.subprocess, "run", return_value=completed) as run:
            result = fixture.run_fixture(arguments)

        self.assertEqual(0, result)
        run.assert_called_once()
        command = run.call_args.args[0]
        environment = run.call_args.kwargs["env"]
        self.assertEqual(
            [
                str(project_directory / "gradlew"),
                ":server:server:documentFixture",
                "--no-daemon",
                "--no-watch-fs",
                "--max-workers=2",
            ],
            command,
        )
        self.assertEqual(project_directory, run.call_args.kwargs["cwd"])
        self.assertFalse(self.EXISTING_USERNAME in repr(command))
        self.assertFalse(self.EXISTING_PASSWORD in repr(command))
        self.assertFalse(self.EXISTING_USERNAME in repr(environment))
        self.assertFalse(self.EXISTING_PASSWORD in repr(environment))
        self.assertEqual(
            {
                "DOCUMENT_FIXTURE_TEST_AMBIENT": "preserved",
                fixture.FIXTURE_ACTION_ENV: "seed",
                fixture.FIXTURE_STATE_DIR_ENV: str(state_dir.resolve()),
                fixture.FIXTURE_CONFIRM_TARGET_ENV: "fixture.example:5100",
            },
            environment,
        )
        account_payload = (state_dir / fixture.ACCOUNT_FILE_NAME).read_text(encoding="utf-8")
        self.assertIn(self.EXISTING_USERNAME, account_payload)
        self.assertIn(self.EXISTING_PASSWORD, account_payload)


if __name__ == "__main__":
    unittest.main()
