#!/usr/bin/env python3
"""One-slot OpenVINO NPU adapter for the Accord Enhanced-LRC queue."""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
import wave
from pathlib import Path
from typing import Any

import imageio_ffmpeg
import numpy as np
import openvino_genai as ov_genai


HEARTBEAT_SECONDS = 60
PUNCTUATION_END = re.compile(r"[.!?][\"')\]]*$")


def request_json(base_url: str, path: str, payload: dict[str, Any]) -> tuple[int, dict[str, Any] | None]:
    body = json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Server returned HTTP {exc.code}: {raw[-1000:]}") from exc


def download(base_url: str, path: str, target: Path) -> None:
    partial = target.with_suffix(target.suffix + ".partial")
    with urllib.request.urlopen(base_url.rstrip("/") + path, timeout=600) as response, partial.open("wb") as output:
        while block := response.read(1024 * 1024):
            output.write(block)
    os.replace(partial, target)


def heartbeat(stop: threading.Event, base_url: str, lease_id: str, worker: str) -> None:
    while not stop.wait(HEARTBEAT_SECONDS):
        try:
            request_json(base_url, f"/v1/enhanced-lrc/gpu/jobs/{lease_id}/heartbeat", {"worker": worker})
        except Exception:
            logging.warning("NPU lease heartbeat failed for %s; retrying", lease_id, exc_info=True)


def decode(path: Path, wav_path: Path) -> np.ndarray:
    subprocess.run(
        [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-v", "error", "-i", str(path), "-ar", "16000", "-ac", "1", str(wav_path)],
        check=True,
        timeout=600,
    )
    with wave.open(str(wav_path), "rb") as source:
        if source.getframerate() != 16000 or source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise RuntimeError("FFmpeg produced an unexpected WAV format")
        return np.frombuffer(source.readframes(source.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0


def stamp(seconds: float, brackets: str) -> str:
    value = max(0, round(seconds * 100))
    minutes, value = divmod(value, 6000)
    secs, centis = divmod(value, 100)
    return f"{brackets[0]}{minutes:02d}:{secs:02d}.{centis:02d}{brackets[1]}"


def transcribe(pipeline: Any, audio: np.ndarray) -> list[tuple[float, float, str]]:
    """Bound static-NPU decoder shapes by transcribing independent short windows."""
    chunk_samples = 25 * 16000
    words: list[tuple[float, float, str]] = []
    for start in range(0, len(audio), chunk_samples):
        chunk = audio[start:start + chunk_samples]
        if len(chunk) < 1600:
            continue
        offset = start / 16000.0
        result = pipeline.generate(
            chunk,
            return_timestamps=True,
            word_timestamps=True,
            language="<|en|>",
            task="transcribe",
        )
        words.extend((float(word.start_ts) + offset, float(word.end_ts) + offset, str(word.word)) for word in result.words)
    return words


def render(words: list[tuple[float, float, str]], tags: dict[str, str], duration: float) -> str:
    if len(words) < 5:
        raise RuntimeError(f"NPU returned only {len(words)} timed words")
    headers = ["[by:Accord Enhanced LRC (npu:openvino-whisper-small)]", "[re:Accord]", "[ve:1.0]"]
    for key, label in (("artist", "ar"), ("title", "ti"), ("album", "al")):
        if tags.get(key):
            headers.append(f"[{label}:{tags[key]}]")
    headers.append(f"[length:{int(duration // 60):02d}:{duration % 60:05.2f}]")
    lines: list[str] = []
    group: list[Any] = []
    for word in words:
        gap = word[0] - group[-1][1] if group else 0.0
        if group and (gap > 1.1 or len(group) >= 10 or PUNCTUATION_END.search(group[-1][2].strip())):
            lines.append(stamp(group[0][0], "[]") + "".join(stamp(item[0], "<>") + item[2] for item in group) + stamp(group[-1][1], "<>"))
            group = []
        group.append(word)
    if group:
        lines.append(stamp(group[0][0], "[]") + "".join(stamp(item[0], "<>") + item[2] for item in group) + stamp(group[-1][1], "<>"))
    return "\n".join(headers + [""] + lines) + "\n"


def run(base_url: str, worker: str, model: Path, work_root: Path) -> None:
    work_root.mkdir(parents=True, exist_ok=True)
    logging.info("Compiling OpenVINO Whisper model on NPU: %s", model)
    pipeline = ov_genai.WhisperPipeline(model, "NPU", word_timestamps=True, STATIC_PIPELINE=True)
    logging.info("NPU worker ready: %s", worker)
    while True:
        lease: dict[str, Any] | None = None
        target: Path | None = None
        wav_path: Path | None = None
        try:
            status, lease = request_json(base_url, "/v1/enhanced-lrc/gpu/lease", {"worker": worker, "capability": "npu"})
            if status == 204 or lease is None:
                time.sleep(15)
                continue
            lease_id = str(lease["leaseId"])
            suffix = Path(str(lease.get("name") or "audio.bin")).suffix or ".audio"
            target = work_root / f"{lease_id}{suffix}"
            wav_path = work_root / f"{lease_id}.wav"
            stop = threading.Event()
            thread = threading.Thread(target=heartbeat, args=(stop, base_url, lease_id, worker), daemon=True)
            thread.start()
            started = time.monotonic()
            try:
                download(base_url, str(lease["audioPath"]), target)
                audio = decode(target, wav_path)
                words = transcribe(pipeline, audio)
                content = render(words, {str(k).casefold(): str(v) for k, v in dict(lease.get("tags") or {}).items()}, float(lease["duration"]))
            finally:
                stop.set()
                thread.join(timeout=5)
            elapsed = time.monotonic() - started
            request_json(
                base_url,
                f"/v1/enhanced-lrc/gpu/jobs/{lease_id}/result",
                {"content": content, "source": f"npu:{worker}:openvino-whisper-small", "aligned": len(words), "fallback": 0, "elapsedSeconds": round(elapsed, 3)},
            )
            logging.info("COMPLETE %s elapsed=%.1fs words=%d", lease.get("path"), elapsed, len(words))
        except Exception as exc:
            logging.exception("NPU worker cycle failed")
            if lease:
                try:
                    message = f"{type(exc).__name__}: {exc}"
                    failure = {"retry": True, "message": message}
                    if "padded_input_ids.get_size() >= tokens.size()" in message:
                        failure["rerouteLane"] = "cuda"
                    request_json(base_url, f"/v1/enhanced-lrc/gpu/jobs/{lease['leaseId']}/fail", failure)
                except Exception:
                    logging.exception("Could not release failed NPU lease; it will expire")
            time.sleep(10)
        finally:
            if target:
                target.unlink(missing_ok=True)
            if wav_path:
                wav_path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://127.0.0.1:18097")
    parser.add_argument("--worker", default="glenm-panther-lake-npu")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--work-root", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run(args.server, args.worker, args.model, args.work_root)


if __name__ == "__main__":
    main()
