#!/usr/bin/env python3
"""Run a shipped Windows EXE/JBR against a disposable, non-GUI bootstrap payload."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
import zipfile


# GetProcessById holds the OS process handle before waiting/killing. Recheck the
# CIM creation time so PID reuse cannot turn cleanup into a different process.
PROCESS_CONTROL = r"""
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($env:TEAMTALK_SMOKE_ROOT) + [IO.Path]::DirectorySeparatorChar
$earliest = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$env:TEAMTALK_SMOKE_STARTED).UtcDateTime
$records = @(Get-CimInstance Win32_Process | Where-Object {
    $_.CreationDate -and $_.CreationDate.ToUniversalTime() -ge $earliest -and (
        ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) -or
        ($_.CommandLine -and $_.CommandLine.Contains($env:TEAMTALK_SMOKE_TOKEN))
    )
})
$waiting = $env:TEAMTALK_SMOKE_PROCESS_MODE -eq 'wait'
if ($waiting) {
    $records = @($records | Where-Object { $_.ProcessId -eq [int]$env:TEAMTALK_SMOKE_PID })
    if ($records.Count -ne 1) { throw 'Reported JVM exited before its exit-code handshake' }
}
foreach ($record in $records) {
    try { $process = [Diagnostics.Process]::GetProcessById($record.ProcessId); $null = $process.Handle }
    catch [ArgumentException] { if ($waiting) { throw }; continue }
    try {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.ProcessId)"
        if (!$current -or $current.CreationDate -ne $record.CreationDate) {
            if ($waiting) { throw 'Reported JVM identity changed' }
            continue
        }
        if ($waiting) {
            if ($process.HasExited) { throw 'Reported JVM exited before release' }
            [IO.File]::WriteAllText([IO.Path]::Combine($root, 'probe.release'), 'exit')
        } else {
            if (!$process.HasExited) { $process.Kill() }
        }
        if (!$process.WaitForExit(10000)) { throw "Fixture process $($record.ProcessId) did not exit" }
        if ($waiting -and $process.ExitCode -ne 0) { throw "Fixture JVM exited with code $($process.ExitCode)" }
    } finally { $process.Dispose() }
}
"""


def process_options():
    return {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}


def control_processes(environment, mode, pid=0):
    encoded = base64.b64encode(PROCESS_CONTROL.encode("utf-16le")).decode("ascii")
    subprocess.run(
        ["pwsh", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
        env={**environment, "TEAMTALK_SMOKE_PROCESS_MODE": mode, "TEAMTALK_SMOKE_PID": str(pid)},
        check=True, timeout=30, **process_options(),
    )


def prepare_payload(root, installation, javac, token):
    """Replace the seed only inside the temporary installation copy."""
    classes = root / "classes"
    classes.mkdir()
    source = Path(__file__).parent / "fixtures/windows-shell/TeamTalkMain.java"
    subprocess.run([str(javac), "--release", "21", "-encoding", "UTF-8", "-d", str(classes), str(source)],
                   check=True, timeout=30, **process_options())
    jar = root / "smoke.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes).as_posix())
    descriptor = (
        "version=0.0.0-ci-smoke\nbuild=1\nminShellAbi=1\n"
        f"buildIdentity=ci-smoke-{token}\nfiles.count=1\n"
        "file.0.path=lib/smoke.jar\n"
        f"file.0.sha256={hashlib.sha256(jar.read_bytes()).hexdigest()}\nfile.0.size={jar.stat().st_size}\n"
    )
    with zipfile.ZipFile(installation / "app/seed-payload.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("payload.properties", descriptor)
        archive.write(jar, "lib/smoke.jar")


def fixture_environment(root, token):
    environment = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    for key, directory in {"USERPROFILE": "home", "APPDATA": "home/Roaming", "LOCALAPPDATA": "home/Local",
                           "TEMP": "tmp", "TMP": "tmp", "TEAMTALK_PAYLOAD_DIR": "versions"}.items():
        path = root / directory
        path.mkdir(parents=True, exist_ok=True)
        environment[key] = str(path)
    environment.update({
        "JAVA_TOOL_OPTIONS": f'-Djava.awt.headless=true -Duser.home="{root / "home"}"',
        "TEAMTALK_SMOKE_ROOT": str(root), "TEAMTALK_SMOKE_TOKEN": token,
        "TEAMTALK_SMOKE_STARTED": str(int(time.time() * 1000)),
    })
    return environment


def validate_report(report, root, installation, executable, arguments):
    if not report.get("ready") or report.get("headless") != "true":
        raise AssertionError(f"Fixture did not enter in headless mode: {report}")
    if Path(report["javaHome"]).resolve() != (installation / "runtime").resolve():
        raise AssertionError(f"EXE used a different Java runtime: {report['javaHome']}")
    if Path(report["userHome"]).resolve() != (root / "home").resolve():
        raise AssertionError("JVM did not isolate user.home")
    if Path(report["launcher"]).resolve() != executable.resolve():
        raise AssertionError(f"Bootstrap received a different restart launcher: {report['launcher']}")
    if Path(report["payloadDir"]).resolve().parent != (root / "versions").resolve():
        raise AssertionError("Bootstrap did not isolate the payload root")
    if (report["version"], report["build"], report["shellAbi"]) != ("0.0.0-ci-smoke", "1", "1"):
        raise AssertionError(f"Bootstrap payload context differs: {report}")
    if report["args"] != arguments:
        raise AssertionError(f"Argument quoting failed: {report['args']}")
    if not (root / "versions/current.properties").is_file():
        raise AssertionError("Bootstrap did not seed its current pointer")


def run_probe(root, installation, executable, environment):
    arguments = [f'--teamtalk-ci-fixture={environment["TEAMTALK_SMOKE_TOKEN"]}', "argument with spaces", "中文参数"]
    command = [str(executable), *arguments]
    log = root / "probe.log"
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=root, env=environment,
                                   stdout=output, stderr=subprocess.STDOUT, **process_options())
        try:
            report_file = root / "probe.json"
            deadline = time.monotonic() + 60
            while not report_file.exists():
                if process.poll() not in (None, 0):
                    raise AssertionError(f"EXE failed with exit code {process.returncode}")
                if time.monotonic() > deadline:
                    raise TimeoutError("No report from the fixture JVM")
                time.sleep(0.1)
            report = json.loads(report_file.read_text(encoding="utf-8"))
            print(json.dumps(report, ensure_ascii=False), flush=True)
            validate_report(report, root, installation, executable, arguments)
            control_processes(environment, "wait", report["pid"])
            if process.wait(timeout=10) != 0:
                raise AssertionError(f"EXE failed with exit code {process.returncode}")
            print("Packaged EXE/JBR probe completed; fixture JVM exit code = 0", flush=True)
        finally:
            # Popen retains a handle to this exact launcher even if Windows reuses its PID.
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
            print(log.read_text(encoding="utf-8", errors="replace"), end="", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("This smoke test requires Windows; it must execute the generated EXE")
    javac = Path(os.environ["JAVA_HOME"]) / "bin/javac.exe"
    if not javac.is_file() or shutil.which("pwsh") is None:
        parser.error("JDK 21 javac and PowerShell 7 must be available")
    with tempfile.TemporaryDirectory(prefix="teamtalk shell smoke ") as directory:
        root = Path(directory).resolve()
        environment = fixture_environment(root, uuid.uuid4().hex)
        try:
            unpacked = root / "package"
            with zipfile.ZipFile(args.archive.resolve()) as archive:
                archive.extractall(unpacked)
            installations = [path for path in unpacked.iterdir() if (path / "runtime/bin/java.exe").is_file()]
            if len(installations) != 1:
                raise AssertionError("Portable archive must contain exactly one bundled JBR installation")
            installation = installations[0]
            executable = installation / f"{installation.name}.exe"
            if not executable.is_file():
                raise AssertionError("Portable archive is missing its native launcher")
            prepare_payload(root, installation, javac, environment["TEAMTALK_SMOKE_TOKEN"])
            run_probe(root, installation, executable, environment)
        finally:
            control_processes(environment, "kill")


if __name__ == "__main__":
    main()
