#!/usr/bin/env python3
"""Reject authentication payloads in the real log sink using a synthetic, account-free canary."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("companion", type=Path)
    parser.add_argument("platform", type=Path)
    parser.add_argument("d8", type=Path)
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    source = Path(__file__).with_name("TestAuthDiagnostics.java")
    remote = None
    try:
        with tempfile.TemporaryDirectory(prefix="microg-log-check-") as folder:
            work = Path(folder)
            subprocess.run(["javac", "--release", "11", "-cp", str(args.platform),
                            "-d", str(work), str(source)], check=True, capture_output=True)
            subprocess.run([str(args.d8), "--lib", str(args.platform), "--min-api", "28",
                            "--output", str(work / "checks.jar"), *map(str, work.glob("*.class"))],
                           check=True, capture_output=True)
            remote = subprocess.check_output([*adb, "shell", "mktemp", "-d",
                "/data/local/tmp/microg-log-check-XXXXXXXX"], text=True).strip()
            if not re.fullmatch(r"/data/local/tmp/microg-log-check-[A-Za-z0-9]+", remote):
                remote = None
                raise RuntimeError("Invalid remote staging directory")
            for local, name in ((work / "checks.jar", "checks.jar"), (args.companion, "companion.apk")):
                subprocess.run([*adb, "push", str(local), remote + "/" + name], check=True, capture_output=True)
            subprocess.run([*adb, "shell", "chmod", "400", remote + "/checks.jar", remote + "/companion.apk"], check=True)
            canary = "synthetic-auth-" + uuid.uuid4().hex
            output = subprocess.check_output([*adb, "shell", f"CLASSPATH={remote}/checks.jar:{remote}/companion.apk",
                "app_process", "/system/bin", "TestAuthDiagnostics", canary], text=True)
            match = re.search(r"^AUTH_DIAGNOSTICS_PID=([0-9]+)$", output, re.M)
            if not match:
                raise RuntimeError("The logging path did not reach the account-service guard")
            logs = subprocess.check_output([*adb, "logcat", "-d", "--pid=" + match[1],
                "-s", "GmsAuthManagerSvc:D", "*:S"], text=True)
            if "clearToken" not in logs:
                raise AssertionError("The production diagnostic was not observed")
            if canary in logs:
                raise AssertionError("Authentication canary leaked into logcat")
            print("AUTH_DIAGNOSTICS=PASS; production sink observed, account access blocked, canary absent")
    finally:
        if remote:
            subprocess.run([*adb, "shell", "rm", "-f", remote + "/checks.jar", remote + "/companion.apk"], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run([*adb, "shell", "rmdir", remote], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
