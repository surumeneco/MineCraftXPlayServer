#!/usr/bin/env python3
"""Read-only process CPU/RSS sampler for Minecraft Paper; optional spark TPS via RCON."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time
import zipfile

PLUGIN_NAMES = ("WonderfulGenomeLib", "WhatAWonderfulChicken", "WhatAWonderfulWolf")


def proc_stats(pid, proc_root=Path("/proc")):
    raw = (proc_root / str(pid) / "stat").read_text(encoding="utf-8")
    end = raw.rfind(")")
    if end < 0 or not raw.startswith(f"{pid} ("):
        raise ValueError("unexpected Linux process stat format")
    fields = raw[end + 2:].split()
    if len(fields) < 22:
        raise ValueError("incomplete Linux process stat")
    return {
        "cpu_ticks": int(fields[11]) + int(fields[12]),
        "start_ticks": int(fields[19]),
        "rss_bytes": int(fields[21]) * os.sysconf("SC_PAGE_SIZE"),
        "threads": int(fields[17]),
    }


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def plugin_metadata(plugins_dir):
    result = []
    for name in PLUGIN_NAMES:
        for jar in sorted(plugins_dir.glob(f"{name}*.jar")):
            version = None
            try:
                with zipfile.ZipFile(jar) as archive:
                    yml = archive.read("plugin.yml").decode("utf-8")
                match = re.search(r"^version:\s*['\"]?([^'\"\r\n]+)", yml, re.MULTILINE)
                if match:
                    version = match.group(1).strip()
            except (OSError, KeyError, zipfile.BadZipFile, UnicodeError):
                pass
            result.append({"file": jar.name, "sha256": sha256_file(jar), "plugin_yml_version": version})
    return result


def spark_tps(rcon_client):
    # RCON_PASSWORD remains in the environment; never persist it or stderr.
    try:
        done = subprocess.run(
            [sys.executable, str(rcon_client), "spark tps"],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"error": type(exc).__name__}
    if done.returncode:
        return {"error": "rcon_failed", "exit_code": done.returncode}
    return {"output": done.stdout.strip()[:12000]}


def quantile(values, probability):
    if not values:
        raise ValueError("empty values")
    ordered = sorted(values)
    return ordered[max(0, math.ceil(probability * len(ordered)) - 1)]


def run(args):
    out = Path(args.out).expanduser()
    if out.exists() and any(out.iterdir()):
        raise ValueError(f"output directory is not empty: {out}")
    original = proc_stats(args.pid)
    out.mkdir(parents=True, exist_ok=True)
    tick_hz = os.sysconf("SC_CLK_TCK")
    metadata = {
        "schema": 1,
        "scenario": args.scenario,
        "start_utc": datetime.now(timezone.utc).isoformat(),
        "pid": args.pid,
        "pid_start_ticks": original["start_ticks"],
        "sample_interval_sec": args.interval,
        "duration_sec_requested": args.duration,
        "spark_tps_requested": args.rcon,
        "plugins": plugin_metadata(Path(args.plugins_dir)),
        "note": "cpu_cores is process CPU seconds / elapsed wall seconds, NOT TPS/MSPT.",
    }
    (out / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    started = time.monotonic()
    prior_time, prior = started, original
    core_samples = []
    deadline = started + args.duration
    with (out / "samples.jsonl").open("w", encoding="utf-8") as log:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(args.interval, remaining))
            now = time.monotonic()
            current = proc_stats(args.pid)
            if current["start_ticks"] != original["start_ticks"]:
                raise RuntimeError("PID was reused during capture")
            wall = now - prior_time
            ticks = current["cpu_ticks"] - prior["cpu_ticks"]
            if wall <= 0 or ticks < 0:
                raise RuntimeError("invalid CPU counter change")
            cpu_cores = ticks / tick_hz / wall
            row = {
                "elapsed_sec": round(now - started, 3),
                "wall_interval_sec": round(wall, 3),
                "process_cpu_seconds": round(ticks / tick_hz, 6),
                "cpu_cores": round(cpu_cores, 5),
                "rss_bytes": current["rss_bytes"],
                "threads": current["threads"],
            }
            if args.rcon:
                row["spark_tps"] = spark_tps(Path(args.rcon_client))
            log.write(json.dumps(row, ensure_ascii=False) + "\n")
            log.flush()
            core_samples.append(cpu_cores)
            prior_time, prior = now, current
    summary = {
        "samples": len(core_samples),
        "elapsed_sec": round(time.monotonic() - started, 3),
        "cpu_cores_mean": round(statistics.fmean(core_samples), 5),
        "cpu_cores_median": round(statistics.median(core_samples), 5),
        "cpu_cores_p95": round(quantile(core_samples, .95), 5),
        "cpu_cores_p99": round(quantile(core_samples, .99), 5),
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pid", required=True, type=int, help="Paper JVM PID, not Launcher")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--out", required=True, help="empty output folder outside Git")
    parser.add_argument("--duration", default=120, type=float)
    parser.add_argument("--interval", default=10, type=float)
    parser.add_argument("--plugins-dir", default="plugins")
    parser.add_argument("--rcon", action="store_true", help="collect spark tps text")
    parser.add_argument("--rcon-client", default="deploy/rcon.py")
    args = parser.parse_args()
    if args.pid <= 0 or not math.isfinite(args.duration) or not math.isfinite(args.interval) or args.duration <= 0 or args.interval <= 0:
        parser.error("pid, duration and interval must be positive and finite")
    if args.rcon and not Path(args.rcon_client).is_file():
        parser.error("RCON client not found")
    try:
        summary = run(args)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"capture failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
