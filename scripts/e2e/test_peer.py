"""TestPeer Gradle invocation contract tests."""

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.e2e import peer


class TestPeerCommandTest(unittest.TestCase):
    def test_reads_report_from_nested_server_module(self) -> None:
        with tempfile.TemporaryDirectory(prefix="teamtalk-peer-report-") as project_root:
            report_dir = Path(project_root) / "server/server/build/test-results/test"
            report_dir.mkdir(parents=True)
            report = report_dir / "TEST-com.virjar.tk.server.e2e.TestPeer.xml"
            report.write_text(
                "<testsuite><system-out>peer-output</system-out></testsuite>",
                encoding="utf-8",
            )
            self.assertEqual("\npeer-output", peer.TestPeer(project_root)._read_xml_stdout("whoami"))

    def test_run_always_executes_test_with_cold_build_timeout(self) -> None:
        with tempfile.TemporaryDirectory(prefix="teamtalk-peer-test-") as project_root:
            test_peer = peer.TestPeer(project_root)
            completed = subprocess.CompletedProcess(
                args=[],
                returncode=0,
                stdout="peer-output",
                stderr="",
            )

            with mock.patch.object(
                peer.subprocess,
                "run",
                return_value=completed,
            ) as run, mock.patch.object(
                test_peer,
                "_read_xml_stdout",
                return_value="",
            ):
                self.assertEqual("peer-output", test_peer._run("whoami"))
                self.assertEqual("peer-output", test_peer._run("whoami"))

        expected_command = [
            str(Path(project_root) / "gradlew"),
            ":server:server:test",
            "--tests",
            "com.virjar.tk.server.e2e.TestPeer.whoami",
            "--rerun-tasks",
            "--no-watch-fs",
            "--no-daemon",
            "--max-workers=2",
            "-q",
            "-Dtk.e2e.remote=true",
        ]
        self.assertEqual(2, run.call_count)
        for invocation in run.call_args_list:
            self.assertEqual((expected_command,), invocation.args)
            self.assertEqual(project_root, invocation.kwargs["cwd"])
            self.assertTrue(invocation.kwargs["capture_output"])
            self.assertTrue(invocation.kwargs["text"])
            self.assertEqual(
                peer.TEST_PEER_TIMEOUT_SECONDS,
                invocation.kwargs["timeout"],
            )
        self.assertEqual(10 * 60, peer.TEST_PEER_TIMEOUT_SECONDS)


if __name__ == "__main__":
    unittest.main()
