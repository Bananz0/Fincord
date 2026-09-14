#!/usr/bin/env python3
"""Laptop CUDA worker for the Phastos Enhanced LRC queue."""

from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


HEARTBEAT_SECONDS = int(os.environ.get("LRC_HEARTBEAT_SECONDS", "60"))


def request_json(base_url: str, token: str, path: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any] | None]:
    body = json.dumps(payload, ensure_ascii=False).encode()
    headers = {"Content-Type": "application/json", "Content-Length": str(len(body))}
    if token:
        headers["X-Enhanced-LRC-Token"] = token
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=body,
        method="POST",
        headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Server returned HTTP {exc.code}: {raw[-1000:]}") from exc


def download(base_url: str, token: str, path: str, target: Path) -> None:
    headers = {"X-Enhanced-LRC-Token": token} if token else {}
    request = urllib.request.Request(base_url.rstrip("/") + path, headers=headers)
    partial = target.with_suffix(target.suffix + ".partial")
    with urllib.request.urlopen(request, timeout=10 * 60) as response, partial.open("wb") as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            output.write(block)
    os.replace(partial, target)


def heartbeat_loop(
    stop: threading.Event,
    base_url: str,
    token: str,
    lease_id: str,
    worker_name: str,
) -> None:
    """Keep a long-running download/transcription lease alive until submission."""
    while not stop.wait(HEARTBEAT_SECONDS):
        try:
            request_json(
                base_url,
                token,
                f"/v1/enhanced-lrc/gpu/jobs/{lease_id}/heartbeat",
                {"worker": worker_name},
            )
        except Exception:
            # A temporary tunnel outage must not kill an otherwise valid result.
            logging.warning("Could not heartbeat lease %s; will retry", lease_id, exc_info=True)


def run(base_url: str, token_file: Path | None, worker_name: str, work_root: Path) -> None:
    import enhanced_lrc as lrc

    token = token_file.read_text(encoding="utf-8").strip() if token_file else ""
    work_root.mkdir(parents=True, exist_ok=True)
    logging.info(
        "Worker=%s device=%s:%d whisper=%s compute=%s server=%s",
        worker_name, lrc.LRC_DEVICE, lrc.LRC_DEVICE_INDEX,
        lrc.WHISPER_MODEL, lrc.WHISPER_COMPUTE_TYPE, base_url,
    )
    while True:
        lease: dict[str, Any] | None = None
        try:
            capability = "cuda" if lrc.LRC_DEVICE == "cuda" else "cpu"
            status, lease = request_json(
                base_url, token, "/v1/enhanced-lrc/gpu/lease",
                {"worker": worker_name, "capability": capability},
            )
            if status == 204 or lease is None:
                time.sleep(15)
                continue
            lease_id = str(lease["leaseId"])
            suffix = Path(str(lease.get("name") or "audio.bin")).suffix or ".audio"
            target = work_root / f"{lease_id}{suffix}"
            started = time.monotonic()
            heartbeat_stop = threading.Event()
            heartbeat = threading.Thread(
                target=heartbeat_loop,
                args=(heartbeat_stop, base_url, token, lease_id, worker_name),
                name=f"lease-heartbeat-{lease_id}",
                daemon=True,
            )
            heartbeat.start()
            try:
                download(base_url, token, str(lease["audioPath"]), target)
                tags = {str(key): str(value) for key, value in dict(lease.get("tags") or {}).items()}
                lines = [(float(item[0]), str(item[1])) for item in lease.get("lines") or []]
                canonical = [str(line) for line in lease.get("canonicalLines") or []]
                content, source, aligned, fallback = lrc.generate_content(
                    target,
                    tags,
                    float(lease["duration"]),
                    str(lease.get("source") or "gpu"),
                    lines,
                    canonical,
                )
            finally:
                heartbeat_stop.set()
                heartbeat.join(timeout=5)
            elapsed = time.monotonic() - started
            result = {
                "content": content,
                "source": f"gpu:{worker_name}:{source}",
                "aligned": aligned,
                "fallback": fallback,
                "elapsedSeconds": round(elapsed, 3),
            }
            request_json(base_url, token, f"/v1/enhanced-lrc/gpu/jobs/{lease_id}/result", result)
            realtime = float(lease["duration"]) / max(elapsed, 0.001)
            logging.info(
                "COMPLETE %s duration=%.1fs elapsed=%.1fs speed=%.1fx words=%d source=%s",
                lease.get("path"), float(lease["duration"]), elapsed, realtime, aligned, source,
            )
            target.unlink(missing_ok=True)
        except (lrc.NeedsReview, lrc.PermanentSkip) as exc:
            logging.warning("REVIEW %s: %s", lease.get("path") if lease else "unleased", exc)
            if lease:
                try:
                    request_json(
                        base_url, token,
                        f"/v1/enhanced-lrc/gpu/jobs/{lease['leaseId']}/fail",
                        {"retry": False, "message": f"{type(exc).__name__}: {exc}"},
                    )
                except Exception:
                    logging.exception("Could not report review result")
        except Exception as exc:
            logging.exception("GPU worker cycle failed")
            if lease:
                try:
                    request_json(
                        base_url, token,
                        f"/v1/enhanced-lrc/gpu/jobs/{lease['leaseId']}/fail",
                        {"retry": True, "message": f"{type(exc).__name__}: {exc}"},
                    )
                except Exception:
                    logging.exception("Could not release failed lease; it will expire")
            time.sleep(10)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://127.0.0.1:18097")
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--worker", default=f"{socket.gethostname()}-gpu")
    parser.add_argument("--work-root", type=Path, default=Path("/var/lib/accord-gpu-worker/work"))
    args = parser.parse_args()
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    run(args.server, args.token_file, args.worker, args.work_root)


if __name__ == "__main__":
    main()
