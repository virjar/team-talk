"""The restart script must never stop another installation. All process tools are mocked."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "desktop-acceptance.sh"
MOCK_SHELL = r'''
stopped=0
function lsof() {
    print -r -- "$*" >> "$MOCK_COMMANDS"
    (( stopped )) || print -r -- "$MOCK_OWNER"
}
function curl() {
    print -r -- "$*" >> "$MOCK_COMMANDS"
    [[ "$MOCK_PING" != unavailable ]] || return 7
    print -r -- "$MOCK_PING"
}
function ps() {
    (( stopped )) && return 1
    if [[ "$*" == *args=* ]]; then
        print -r -- "$MOCK_PROCESS"
    else
        print -r -- "Sun Sep  6 12:00:00 2026"
    fi
}
function kill() {
    print -r -- "$*" >> "$MOCK_SIGNALS"
    stopped=1
}
function sleep() { return 0; }
source "$ACCEPTANCE_SCRIPT" kill
'''


class DesktopAcceptanceIsolationTest(unittest.TestCase):
    def run_cleanup(self, *, owner="43123", reported_pid=43123, process=None, ping=None):
        with tempfile.TemporaryDirectory() as temporary:
            signals = Path(temporary) / "signals"
            commands = Path(temporary) / "commands"
            env = dict(os.environ, ACCEPTANCE_SCRIPT=str(SCRIPT),
                       TEAMTALK_DESKTOP_TEST_PORT="18081",
                       MOCK_SIGNALS=str(signals), MOCK_COMMANDS=str(commands),
                       MOCK_OWNER=owner,
                       MOCK_PROCESS=process or (
                           "/jdk/bin/java -Dtk.desktop.instance.token=selected-instance "
                           "-cp /fixture/libs com.virjar.tk.desktop.TeamTalkMain"),
                       MOCK_PING=ping or json.dumps({
                           "status": "ok", "pid": reported_pid,
                           "instanceToken": "selected-instance"}))
            result = subprocess.run(["zsh", "-c", MOCK_SHELL], env=env, text=True,
                                    capture_output=True, timeout=10)
            return (result, signals.read_text() if signals.exists() else "",
                    commands.read_text() if commands.exists() else "")

    def test_only_verified_listener_receives_a_signal(self):
        result, signals, commands = self.run_cleanup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("-TERM 43123\n", signals)
        self.assertIn("-iTCP:18081", commands)
        self.assertIn("http://127.0.0.1:18081/ping", commands)
        self.assertNotIn("18080", commands)

    def test_other_application_or_disagreeing_identity_is_left_running(self):
        cases = [
            {"reported_pid": 99999},
            {"owner": "43123\n99999"},
            {"process": "/jdk/bin/java -cp /fixture/libs com.example.OtherMain"},
            {"process": "/jdk/bin/java -Dtk.desktop.instance.token=other-instance "
                        "com.virjar.tk.desktop.TeamTalkMain"},
            {"ping": "unavailable"},
        ]
        for case in cases:
            with self.subTest(case=case):
                result, signals, _ = self.run_cleanup(**case)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual("", signals)

    def test_unused_port_never_searches_for_other_instances(self):
        result, signals, commands = self.run_cleanup(owner="")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", signals)
        self.assertNotIn("/ping", commands)


if __name__ == "__main__":
    unittest.main()
