"""Regression tests for read-only Paper process performance capture."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("capture_performance", ROOT / "deploy" / "capture-performance.py")
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


class ProcessStatTest(unittest.TestCase):
    def test_stat_parsing_with_parentheses_and_spaces(self):
        fields = ["S"] + ["0"] * 21
        fields[11], fields[12] = "500", "300"
        fields[17], fields[19], fields[21] = "42", "9123", "11"
        with tempfile.TemporaryDirectory() as tmp:
            entry = Path(tmp) / "123"
            entry.mkdir()
            (entry / "stat").write_text("123 (Java Worker (Paper)) " + " ".join(fields))
            with patch.object(perf.os, "sysconf", return_value=4096):
                result = perf.proc_stats(123, Path(tmp))
        self.assertEqual(result, {
            "cpu_ticks": 800, "start_ticks": 9123,
            "rss_bytes": 45056, "threads": 42,
        })

    def test_rejects_mismatched_pid_prefix(self):
        with tempfile.TemporaryDirectory() as tmp:
            entry = Path(tmp) / "123"
            entry.mkdir()
            (entry / "stat").write_text("999 (java) S " + "0 " * 22)
            with self.assertRaises(ValueError):
                perf.proc_stats(123, Path(tmp))


class QuantileTest(unittest.TestCase):
    def test_nearest_rank(self):
        self.assertEqual(perf.quantile([1, 2, 3, 4, 5], .95), 5)
        self.assertEqual(perf.quantile([1, 2, 3, 4, 5], .50), 3)
        self.assertEqual(perf.quantile([1, 2, 3, 4, 5], .01), 1)
        with self.assertRaises(ValueError):
            perf.quantile([], .95)


class MetadataTest(unittest.TestCase):
    def test_missing_plugin_jars(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(perf.plugin_metadata(Path(tmp)), [])


if __name__ == "__main__":
    unittest.main()
