"""Cross-host regressions for the isolated Windows launcher fixture."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from windows_shell_smoke import fixture_environment, prepare_launcher_options


class WindowsShellFixtureTest(unittest.TestCase):
    def test_fixture_options_do_not_pollute_launch4j_version_probe(self):
        java = shutil.which("java")
        if java is None:
            self.skipTest("A JDK is required to exercise the actual java -version output")
        with tempfile.TemporaryDirectory(prefix="teamtalk shell fixture ") as directory:
            root = Path(directory).resolve()
            installation = root / "package" / "TeamTalk"
            installation.mkdir(parents=True)
            executable = installation / "TeamTalk.exe"
            with patch.dict(os.environ, {
                "JAVA_TOOL_OPTIONS": "--invalid-inherited-option",
                "JDK_JAVA_OPTIONS": "--invalid-inherited-option",
                "_JAVA_OPTIONS": "--invalid-inherited-option",
            }):
                environment = fixture_environment(root, "fixture-version-probe")
            prepare_launcher_options(root, executable)

            # The same version probe used by Launch4j must see the Java version,
            # never the quoted home directory from an injected environment banner.
            result = subprocess.run([java, "-version"], env=environment, capture_output=True,
                                    text=True, check=True, timeout=15)
            first_quoted = re.search(r'"([^"]+)"', result.stdout + result.stderr)
            self.assertIsNotNone(first_quoted)
            self.assertRegex(first_quoted.group(1), r"^\d+(?:\.\d+)*")

            options = (installation / "TeamTalk.l4j.ini").read_text(encoding="utf-8").splitlines()
            self.assertIn("-Djava.awt.headless=true", options)
            self.assertIn(f'-Duser.home="{root / "home"}"', options)
            self.assertFalse((root / "TeamTalk.l4j.ini").exists())
            self.assertEqual(str(root / "versions"), environment["TEAMTALK_PAYLOAD_DIR"])


if __name__ == "__main__":
    unittest.main()
