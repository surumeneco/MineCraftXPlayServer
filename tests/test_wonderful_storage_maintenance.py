import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "deploy" / "minecraft-maintenance.sh"


class WonderfulMigrationMaintenanceTest(unittest.TestCase):
    def run_script(self, wwc_status="WCL-MIGRATION job=job-123 status=SUCCESS processed=2 copied=1 skipped=1 failed=0",
                   www_status="WCL-MIGRATION job=job-123 status=SUCCESS processed=3 copied=1 skipped=2 failed=0",
                   enabled="1"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "deploy").mkdir()
            (root / "deploy" / "rcon.py").touch()
            bin_path = root / "bin"
            bin_path.mkdir()
            def executable(name, source):
                path = bin_path / name
                path.write_text(source, encoding="utf-8")
                path.chmod(0o755)
                return str(path)
            executable("systemctl", """#!/bin/sh
if [ "$1" = "is-active" ]; then exit 0; fi
if [ "$1" = "restart" ]; then printf '%s\\n' "$2" >> "$RESTART_LOG"; exit 0; fi
exit 1
""")
            executable("sleep", "#!/bin/sh\nexit 0\n")
            python_stub = executable("python-stub", """#!/bin/sh
printf '%s\\n' "$2" >> "$RCON_LOG"
case "$2" in
  "wwc storage migrate"|"www storage migrate")
    echo "WCL-MIGRATION job=job-123 status=RUNNING processed=0 copied=0 skipped=0 failed=0" ;;
  "wwc storage status") printf '%s\\n' "$WWC_STATUS" ;;
  "www storage status") printf '%s\\n' "$WWW_STATUS" ;;
esac
exit 0
""")
            rcon_log = root / "rcon.log"
            restart_log = root / "restart.log"
            env = dict(os.environ,
                PATH=str(bin_path) + os.pathsep + os.environ.get("PATH", ""),
                MINECRAFT_SERVER_DIR=str(root),
                PYTHON_BIN=python_stub,
                RCON_LOG=str(rcon_log),
                RESTART_LOG=str(restart_log),
                WWC_STATUS=wwc_status,
                WWW_STATUS=www_status,
                WONDERFUL_MIGRATION_ENABLED=enabled,
                WONDERFUL_MIGRATION_MAX_POLLS="2",
                WONDERFUL_MIGRATION_POLL_SECONDS="0",
            )
            result = subprocess.run(["bash", str(SCRIPT)], env=env,
                                    capture_output=True, text=True, timeout=12)
            commands = rcon_log.read_text(encoding="utf-8").splitlines() if rcon_log.exists() else []
            restarts = restart_log.read_text(encoding="utf-8").splitlines() if restart_log.exists() else []
            return result.returncode, commands, restarts

    def test_migration_runs_before_final_save_and_restart(self):
        status, commands, restarts = self.run_script()
        self.assertEqual(status, 0)
        self.assertEqual(commands[-5:], ["wwc storage migrate", "wwc storage status",
                                         "www storage migrate", "www storage status", "save-all flush"])
        self.assertEqual(restarts, ["minecraft.service"])

    def test_failure_prevents_save_and_restart(self):
        status, commands, restarts = self.run_script(
            wwc_status="WCL-MIGRATION job=job-123 status=FAILED processed=2 copied=0 skipped=0 failed=2")
        self.assertNotEqual(status, 0)
        self.assertNotIn("www storage migrate", commands)
        self.assertNotIn("save-all flush", commands)
        self.assertEqual(restarts, [])

    def test_fake_success_text_or_rcon_exit_zero_is_not_enough(self):
        status, commands, restarts = self.run_script(
            wwc_status="Unknown command. WCL-MIGRATION job=job-123 status=SUCCESS processed=0 failed=0")
        self.assertNotEqual(status, 0)
        self.assertEqual(restarts, [])

    def test_running_never_finishes_cannot_restart(self):
        status, commands, restarts = self.run_script(
            wwc_status="WCL-MIGRATION job=job-123 status=RUNNING processed=1 copied=0 skipped=0 failed=0")
        self.assertNotEqual(status, 0)
        self.assertEqual(commands.count("wwc storage status"), 2)
        self.assertEqual(restarts, [])

    def test_opt_in_required(self):
        status, commands, restarts = self.run_script(enabled="0")
        self.assertEqual(status, 0)
        self.assertNotIn("wwc storage migrate", commands)
        self.assertEqual(commands[-1], "save-all flush")
        self.assertEqual(restarts, ["minecraft.service"])


if __name__ == "__main__":
    unittest.main()
