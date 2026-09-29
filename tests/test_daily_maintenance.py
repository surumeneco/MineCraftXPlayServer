import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
RCON_PATH = ROOT / "deploy" / "rcon.py"
MAINTENANCE_PATH = ROOT / "deploy" / "minecraft-maintenance.sh"
spec = importlib.util.spec_from_file_location("maintenance_rcon", RCON_PATH)
rcon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rcon)


class FakeRconSocket:
    def __init__(self):
        self.response = (
            rcon.encode_packet(1, 2, "")
            + rcon.encode_packet(2, 0, "Saved")
        )
        self.offset = 0
        self.timeout = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def settimeout(self, seconds):
        self.timeout = seconds

    def sendall(self, packet):
        pass

    def recv(self, size):
        chunk = self.response[self.offset:self.offset + size]
        self.offset += len(chunk)
        return chunk


class RconTimeoutTest(unittest.TestCase):
    def test_default_and_save_timeouts_are_used_on_socket(self):
        for requested, expected in ((None, 5.0), ("120", 120.0)):
            with self.subTest(requested=requested):
                sock = FakeRconSocket()
                env = {"RCON_PASSWORD": "test-password"}
                if requested is not None:
                    env["RCON_TIMEOUT_SECONDS"] = requested
                with (
                    patch.dict(os.environ, env, clear=True),
                    patch.object(sys, "argv", ["rcon.py", "save-all flush"]),
                    patch.object(rcon.socket, "create_connection", return_value=sock) as connect,
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    exit_code = rcon.main()
                self.assertEqual(exit_code, 0)
                self.assertEqual(sock.timeout, expected)
                connect.assert_called_once_with(("127.0.0.1", 25575), timeout=expected)

    def test_invalid_timeout_is_rejected_before_connection(self):
        for value in ("", "0", "-1", "nan", "inf", "nonsense"):
            with self.subTest(value=value):
                with (
                    patch.dict(os.environ, {"RCON_PASSWORD": "test", "RCON_TIMEOUT_SECONDS": value}, clear=True),
                    patch.object(sys, "argv", ["rcon.py", "save-all flush"]),
                    patch.object(rcon.socket, "create_connection") as connect,
                    contextlib.redirect_stderr(io.StringIO()),
                ):
                    exit_code = rcon.main()
                self.assertEqual(exit_code, 2)
                connect.assert_not_called()


class MaintenanceScriptTest(unittest.TestCase):
    def run_maintenance(self, fail_save=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            deploy_dir = root / "deploy"
            bin_dir.mkdir()
            deploy_dir.mkdir()
            (deploy_dir / "rcon.py").touch()

            def executable(name, code):
                path = bin_dir / name
                path.write_text(code, encoding="utf-8")
                path.chmod(0o755)
                return path

            executable("systemctl", """#!/bin/sh
if [ "$1" = "is-active" ]; then exit 0; fi
if [ "$1" = "restart" ]; then printf '%s\n' "$2" >> "$RESTART_LOG"; exit 0; fi
exit 1
""")
            executable("sleep", "#!/bin/sh\nexit 0\n")
            python_stub = executable("python-stub", """#!/bin/sh
printf '%s|%s\n' "$RCON_TIMEOUT_SECONDS" "$2" >> "$RCON_LOG"
if [ "$FAIL_SAVE" = "1" ] && [ "$2" = "save-all flush" ]; then exit 1; fi
exit 0
""")
            rcon_log = root / "rcon.log"
            restart_log = root / "restart.log"
            env = dict(os.environ,
                PATH=str(bin_dir) + os.pathsep + os.environ.get("PATH", ""),
                MINECRAFT_SERVER_DIR=str(root),
                PYTHON_BIN=str(python_stub),
                RCON_LOG=str(rcon_log),
                RESTART_LOG=str(restart_log),
                FAIL_SAVE="1" if fail_save else "0",
            )
            proc = subprocess.run(
                ["bash", str(MAINTENANCE_PATH)],
                env=env, capture_output=True, text=True, timeout=10,
            )
            return (
                proc.returncode,
                rcon_log.read_text(encoding="utf-8").splitlines(),
                restart_log.read_text(encoding="utf-8").splitlines()
                if restart_log.exists() else [],
            )

    def test_flush_uses_long_timeout_and_restarts(self):
        status, calls, restarts = self.run_maintenance()
        self.assertEqual(status, 0)
        self.assertEqual(len(calls), 12)
        self.assertEqual(calls[-1], "120|save-all flush")
        self.assertTrue(all(line.startswith("5|say ") for line in calls[:-1]))
        self.assertEqual(restarts, ["minecraft.service"])

    def test_flush_failure_prevents_restart(self):
        status, calls, restarts = self.run_maintenance(fail_save=True)
        self.assertNotEqual(status, 0)
        self.assertEqual(calls[-1], "120|save-all flush")
        self.assertEqual(restarts, [])


if __name__ == "__main__":
    unittest.main()
