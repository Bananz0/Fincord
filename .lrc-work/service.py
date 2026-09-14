#!/usr/bin/env python3
"""Authenticated, asynchronous Demucs stem service for Accord."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import mimetypes
import os
import queue
import re
import secrets
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import enhanced_lrc
import enhanced_lrc_gpu_api


LOG = logging.getLogger("accord-karaoke")
ITEM_ID = re.compile(r"^[0-9a-fA-F-]{32,36}$")

PORT = int(os.environ.get("PORT", "8097"))
JELLYFIN_URL = os.environ.get("JELLYFIN_URL", "http://127.0.0.1:8096").rstrip("/")
MEDIA_ROOT = Path(os.environ.get("MEDIA_ROOT", "/data/media")).resolve()
CACHE_ROOT = Path(os.environ.get("CACHE_ROOT", "/cache")).resolve()
MODEL = os.environ.get("DEMUCS_MODEL", "htdemucs_ft")
WORKERS = max(1, int(os.environ.get("DEMUCS_JOBS", "2")))
MAX_CACHE_BYTES = int(float(os.environ.get("MAX_CACHE_GB", "100")) * 1024**3)
TICKET_LIFETIME = int(os.environ.get("TICKET_LIFETIME_SECONDS", str(7 * 24 * 3600)))

CACHE_ROOT.mkdir(parents=True, exist_ok=True)


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")
    os.replace(tmp, path)


def load_or_create_secret() -> bytes:
    path = CACHE_ROOT / ".stream-secret"
    try:
        return base64.urlsafe_b64decode(path.read_bytes())
    except (FileNotFoundError, ValueError):
        value = secrets.token_bytes(32)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(base64.urlsafe_b64encode(value))
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
        return value


STREAM_SECRET = load_or_create_secret()


@dataclass
class Track:
    item_id: str
    source: Path
    title: str
    fingerprint: str


@dataclass
class Job:
    item_id: str
    fingerprint: str
    title: str
    source: str
    status: str = "idle"
    phase: str = "idle"
    progress: int = 0
    message: str | None = None
    updated_at: int = 0
    last_accessed: int = 0


JOBS: dict[str, Job] = {}
JOBS_LOCK = threading.RLock()
WORK_QUEUE: queue.Queue[Track] = queue.Queue()


def track_dir(item_id: str) -> Path:
    return CACHE_ROOT / item_id.replace("-", "").lower()


def state_path(item_id: str) -> Path:
    return track_dir(item_id) / "state.json"


def output_path(item_id: str) -> Path:
    return track_dir(item_id) / "instrumental.flac"


def persist(job: Job) -> None:
    directory = track_dir(job.item_id)
    directory.mkdir(parents=True, exist_ok=True)
    atomic_json(directory / "state.json", asdict(job))


def load_jobs() -> None:
    for path in CACHE_ROOT.glob("*/state.json"):
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            job = Job(**raw)
            if job.status in {"queued", "processing"}:
                job.status = "idle"
                job.phase = "interrupted"
                job.progress = 0
                job.message = "Preparation was interrupted and can be restarted"
            if job.status == "ready" and not output_path(job.item_id).is_file():
                job.status = "idle"
                job.phase = "idle"
                job.progress = 0
            JOBS[job.item_id] = job
            persist(job)
        except Exception:
            LOG.exception("Ignoring invalid state file %s", path)


def auth_token(headers: Any, query: dict[str, list[str]] | None = None) -> str | None:
    direct = headers.get("X-Emby-Token") or headers.get("X-MediaBrowser-Token")
    if direct:
        return direct.strip()
    authorization = headers.get("Authorization", "")
    match = re.search(r'(?:Token|token)=["\']?([^",\s\']+)', authorization)
    if match:
        return match.group(1)
    if query:
        return query.get("api_key", [None])[0]
    return None


def jellyfin_item(item_id: str, token: str) -> Track:
    url = f"{JELLYFIN_URL}/Items/{urllib.parse.quote(item_id)}?Fields=Path,MediaSources"
    request = urllib.request.Request(url, headers={"X-Emby-Token": token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        if exc.code in (HTTPStatus.UNAUTHORIZED, HTTPStatus.FORBIDDEN, HTTPStatus.NOT_FOUND):
            raise PermissionError("Jellyfin did not permit this track") from exc
        raise RuntimeError(f"Jellyfin returned HTTP {exc.code}") from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError("Jellyfin is unavailable") from exc

    if payload.get("Type") != "Audio":
        raise ValueError("The requested Jellyfin item is not audio")
    raw_path = payload.get("Path")
    if not raw_path:
        raise ValueError("Jellyfin did not provide a source path")
    source = Path(raw_path).resolve(strict=True)
    if source != MEDIA_ROOT and MEDIA_ROOT not in source.parents:
        raise PermissionError("Jellyfin returned a path outside the media library")
    if not source.is_file():
        raise FileNotFoundError("The source audio file is unavailable")
    stat = source.stat()
    fingerprint = hashlib.sha256(
        f"{item_id}:{source}:{stat.st_size}:{stat.st_mtime_ns}:{MODEL}".encode()
    ).hexdigest()[:24]
    return Track(item_id=item_id, source=source, title=str(payload.get("Name") or source.stem), fingerprint=fingerprint)


def current_job(track: Track) -> Job:
    with JOBS_LOCK:
        existing = JOBS.get(track.item_id)
        if existing is None or existing.fingerprint != track.fingerprint:
            if existing is not None:
                output_path(track.item_id).unlink(missing_ok=True)
            existing = Job(
                item_id=track.item_id,
                fingerprint=track.fingerprint,
                title=track.title,
                source=str(track.source),
                updated_at=int(time.time()),
                last_accessed=int(time.time()),
            )
            JOBS[track.item_id] = existing
            persist(existing)
        elif existing.status == "ready" and not output_path(track.item_id).is_file():
            existing.status = "idle"
            existing.phase = "idle"
            existing.progress = 0
            existing.message = None
            persist(existing)
        return existing


def update_job(job: Job, *, status: str, phase: str, progress: int, message: str | None = None) -> None:
    with JOBS_LOCK:
        job.status = status
        job.phase = phase
        job.progress = max(0, min(100, progress))
        job.message = message
        job.updated_at = int(time.time())
        persist(job)


def sign_ticket(item_id: str, fingerprint: str) -> str:
    expiry = int(time.time()) + TICKET_LIFETIME
    payload = f"{item_id}:{fingerprint}:{expiry}".encode()
    signature = hmac.new(STREAM_SECRET, payload, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(payload + b"." + signature).decode().rstrip("=")


def verify_ticket(ticket: str, item_id: str) -> bool:
    try:
        padded = ticket + "=" * (-len(ticket) % 4)
        raw = base64.urlsafe_b64decode(padded)
        payload, signature = raw.rsplit(b".", 1)
        if not hmac.compare_digest(signature, hmac.new(STREAM_SECRET, payload, hashlib.sha256).digest()):
            return False
        ticket_item, fingerprint, expiry = payload.decode().split(":", 2)
        if ticket_item != item_id or int(expiry) < int(time.time()):
            return False
        with JOBS_LOCK:
            job = JOBS.get(item_id)
            return job is not None and job.status == "ready" and job.fingerprint == fingerprint
    except (ValueError, UnicodeDecodeError):
        return False


def public_state(job: Job, *, include_ticket: bool = True) -> dict[str, Any]:
    response: dict[str, Any] = {
        "itemId": job.item_id,
        "status": job.status,
        "phase": job.phase,
        "progress": job.progress,
        "message": job.message,
        "model": MODEL,
    }
    if include_ticket and job.status == "ready":
        ticket = sign_ticket(job.item_id, job.fingerprint)
        response["streamPath"] = f"v1/tracks/{job.item_id}/stream?ticket={urllib.parse.quote(ticket)}"
    return response


def prepare(track: Track) -> Job:
    job = current_job(track)
    with JOBS_LOCK:
        if job.status not in {"ready", "queued", "processing"}:
            update_job(job, status="queued", phase="queued", progress=5, message="Waiting for the separator")
            WORK_QUEUE.put(track)
    return job


def run_separator(track: Track) -> None:
    job = current_job(track)
    directory = track_dir(track.item_id)
    work = directory / "work"
    final = output_path(track.item_id)
    partial = directory / "instrumental.partial.flac"
    try:
        if final.is_file() and job.fingerprint == track.fingerprint:
            update_job(job, status="ready", phase="ready", progress=100)
            return
        shutil.rmtree(work, ignore_errors=True)
        partial.unlink(missing_ok=True)
        work.mkdir(parents=True, exist_ok=True)
        update_job(job, status="processing", phase="separating", progress=15, message="Separating vocals")

        demucs = [
            "python", "-m", "demucs.separate",
            "--two-stems", "vocals",
            "--name", MODEL,
            "--out", str(work),
            "--jobs", str(WORKERS),
            str(track.source),
        ]
        separated = subprocess.run(
            demucs,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=4 * 60 * 60,
            preexec_fn=lambda: os.nice(10),
        )
        if separated.returncode != 0:
            raise RuntimeError(separated.stdout[-4000:] or "Demucs failed without output")

        stem = work / MODEL / track.source.stem / "no_vocals.wav"
        if not stem.is_file():
            candidates = list(work.glob("**/no_vocals.wav"))
            if len(candidates) != 1:
                raise RuntimeError("Demucs completed but produced no instrumental stem")
            stem = candidates[0]

        update_job(job, status="processing", phase="encoding", progress=92, message="Encoding lossless audio")
        encoded = subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(stem), "-map_metadata", "-1",
                "-c:a", "flac", "-compression_level", "5", str(partial),
            ],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=60 * 60,
            preexec_fn=lambda: os.nice(10),
        )
        if encoded.returncode != 0 or not partial.is_file():
            raise RuntimeError(encoded.stdout[-4000:] or "FFmpeg did not create the instrumental")
        os.replace(partial, final)
        shutil.rmtree(work, ignore_errors=True)
        update_job(job, status="ready", phase="ready", progress=100, message=None)
        prune_cache(exclude=track.item_id)
        LOG.info("Instrumental ready for %s (%s)", track.title, track.item_id)
    except subprocess.TimeoutExpired:
        update_job(job, status="failed", phase="failed", progress=0, message="Separation exceeded its time limit")
        LOG.exception("Separation timed out for %s", track.item_id)
    except Exception as exc:
        update_job(job, status="failed", phase="failed", progress=0, message=str(exc)[-500:])
        LOG.exception("Separation failed for %s", track.item_id)
    finally:
        partial.unlink(missing_ok=True)
        shutil.rmtree(work, ignore_errors=True)


def worker() -> None:
    while True:
        track = WORK_QUEUE.get()
        try:
            run_separator(track)
        finally:
            WORK_QUEUE.task_done()


def prune_cache(*, exclude: str) -> None:
    entries: list[tuple[int, str, Path, int]] = []
    total = 0
    with JOBS_LOCK:
        for item_id, job in JOBS.items():
            path = output_path(item_id)
            if not path.is_file():
                continue
            size = path.stat().st_size
            total += size
            entries.append((job.last_accessed, item_id, path, size))
        if total <= MAX_CACHE_BYTES:
            return
        for _, item_id, path, size in sorted(entries):
            if item_id == exclude or total <= MAX_CACHE_BYTES:
                continue
            path.unlink(missing_ok=True)
            job = JOBS[item_id]
            job.status = "idle"
            job.phase = "evicted"
            job.progress = 0
            job.message = None
            persist(job)
            total -= size


class Handler(BaseHTTPRequestHandler):
    server_version = "AccordKaraoke/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        LOG.info("%s %s", self.client_address[0], fmt % args)

    def json_response(self, status: int, value: dict[str, Any]) -> None:
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def error_json(self, status: int, message: str) -> None:
        self.json_response(status, {"error": message})

    def gpu_authorized(self) -> bool:
        return self.client_address[0] in {"127.0.0.1", "::1"} or enhanced_lrc.webhook_authorized(self.headers)

    def parse_track_route(self) -> tuple[str, str] | None:
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        match = re.fullmatch(r"/v1/tracks/([^/]+)(?:/(stream))?", path)
        if not match:
            return None
        item_id = match.group(1)
        if not ITEM_ID.fullmatch(item_id):
            return None
        return item_id, match.group(2) or "state"

    def authorized_track(self, item_id: str) -> Track | None:
        token = auth_token(self.headers)
        if not token:
            self.error_json(HTTPStatus.UNAUTHORIZED, "A Jellyfin token is required")
            return None
        try:
            return jellyfin_item(item_id, token)
        except PermissionError as exc:
            self.error_json(HTTPStatus.FORBIDDEN, str(exc))
        except FileNotFoundError as exc:
            self.error_json(HTTPStatus.NOT_FOUND, str(exc))
        except ValueError as exc:
            self.error_json(HTTPStatus.BAD_REQUEST, str(exc))
        except RuntimeError as exc:
            self.error_json(HTTPStatus.BAD_GATEWAY, str(exc))
        return None

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        gpu_lease = re.fullmatch(r"/v1/enhanced-lrc/gpu/jobs/([A-Za-z0-9_-]+)/(result|fail|heartbeat)", parsed.path.rstrip("/"))
        if parsed.path.rstrip("/") == "/v1/enhanced-lrc/gpu/lease" or gpu_lease:
            if not self.gpu_authorized():
                self.error_json(HTTPStatus.FORBIDDEN, "Invalid Enhanced LRC worker token")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 2 * 1024 * 1024:
                    raise ValueError("Worker body must contain 1 byte to 2 MiB of JSON")
                payload = json.loads(self.rfile.read(length))
                if parsed.path.rstrip("/") == "/v1/enhanced-lrc/gpu/lease":
                    job = enhanced_lrc_gpu_api.lease(
                        str(payload.get("worker") or ""), str(payload.get("capability") or "cuda")
                    )
                    if job is None:
                        self.send_response(HTTPStatus.NO_CONTENT)
                        self.end_headers()
                    else:
                        self.json_response(HTTPStatus.OK, job)
                elif gpu_lease and gpu_lease.group(2) == "result":
                    self.json_response(HTTPStatus.OK, enhanced_lrc_gpu_api.complete(gpu_lease.group(1), payload))
                elif gpu_lease and gpu_lease.group(2) == "heartbeat":
                    self.json_response(
                        HTTPStatus.OK,
                        enhanced_lrc_gpu_api.heartbeat(
                            gpu_lease.group(1), str(payload.get("worker") or "")
                        ),
                    )
                else:
                    self.json_response(HTTPStatus.OK, enhanced_lrc_gpu_api.fail(gpu_lease.group(1), payload))
            except KeyError as exc:
                self.error_json(HTTPStatus.NOT_FOUND, str(exc))
            except (ValueError, json.JSONDecodeError) as exc:
                self.error_json(HTTPStatus.BAD_REQUEST, str(exc))
            except Exception as exc:
                LOG.exception("GPU worker request failed")
                self.error_json(HTTPStatus.INTERNAL_SERVER_ERROR, f"{type(exc).__name__}: {exc}")
            return
        if parsed.path.rstrip("/") == "/v1/enhanced-lrc/enqueue":
            if not enhanced_lrc.webhook_authorized(self.headers):
                self.error_json(HTTPStatus.FORBIDDEN, "Invalid Enhanced LRC webhook token")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 2 * 1024 * 1024:
                    raise ValueError("Webhook body must contain 1 byte to 2 MiB of JSON")
                payload = json.loads(self.rfile.read(length))
                paths = enhanced_lrc.webhook_paths(payload)
                event = str(payload.get("eventType", "lidarr")) if isinstance(payload, dict) else "lidarr"
                if not paths and event.casefold() == "test":
                    self.json_response(HTTPStatus.OK, {"status": "ok", "event": "Test"})
                    return
                if not paths:
                    raise ValueError("No imported audio paths were present in the webhook")
                result = enhanced_lrc.enqueue(paths, reason=f"lidarr:{event}"[:100])
                result["pathsAccepted"] = len(paths)
                self.json_response(HTTPStatus.ACCEPTED, result)
            except (ValueError, json.JSONDecodeError) as exc:
                self.error_json(HTTPStatus.BAD_REQUEST, str(exc))
            return
        route = self.parse_track_route()
        if route is None or route[1] != "state" or not self.path.split("?", 1)[0].endswith("/prepare"):
            # prepare has one more segment, handled explicitly here.
            path = urllib.parse.urlparse(self.path).path.rstrip("/")
            match = re.fullmatch(r"/v1/tracks/([^/]+)/prepare", path)
            if match and ITEM_ID.fullmatch(match.group(1)):
                item_id = match.group(1)
            else:
                self.error_json(HTTPStatus.NOT_FOUND, "Unknown endpoint")
                return
        else:
            item_id = route[0]
        track = self.authorized_track(item_id)
        if track is None:
            return
        job = prepare(track)
        self.json_response(HTTPStatus.ACCEPTED if job.status != "ready" else HTTPStatus.OK, public_state(job))

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        gpu_audio = re.fullmatch(r"/v1/enhanced-lrc/gpu/jobs/([A-Za-z0-9_-]+)/audio", parsed.path.rstrip("/"))
        if gpu_audio:
            if not self.gpu_authorized():
                self.error_json(HTTPStatus.FORBIDDEN, "Invalid Enhanced LRC worker token")
                return
            try:
                self.stream_path(enhanced_lrc_gpu_api.audio_path(gpu_audio.group(1)))
            except (KeyError, FileNotFoundError) as exc:
                self.error_json(HTTPStatus.NOT_FOUND, str(exc))
            return
        if parsed.path.rstrip("/") == "/v1/enhanced-lrc/status":
            if not enhanced_lrc.webhook_authorized(self.headers):
                self.error_json(HTTPStatus.FORBIDDEN, "Invalid Enhanced LRC webhook token")
                return
            self.json_response(HTTPStatus.OK, enhanced_lrc.status())
            return
        if parsed.path.rstrip("/") == "/health":
            self.json_response(HTTPStatus.OK, {
                "status": "ok",
                "model": MODEL,
                "queueDepth": WORK_QUEUE.qsize(),
                "workerBusy": any(j.status == "processing" for j in JOBS.values()),
            })
            return
        route = self.parse_track_route()
        if route is None:
            self.error_json(HTTPStatus.NOT_FOUND, "Unknown endpoint")
            return
        item_id, action = route
        if action == "stream":
            query = urllib.parse.parse_qs(parsed.query)
            ticket = query.get("ticket", [""])[0]
            if not verify_ticket(ticket, item_id):
                self.error_json(HTTPStatus.FORBIDDEN, "Invalid or expired stream ticket")
                return
            self.stream_file(item_id)
            return
        track = self.authorized_track(item_id)
        if track is None:
            return
        job = current_job(track)
        self.json_response(HTTPStatus.OK, public_state(job))

    def do_HEAD(self) -> None:
        self.do_GET()

    def stream_file(self, item_id: str) -> None:
        path = output_path(item_id)
        if not path.is_file():
            self.error_json(HTTPStatus.NOT_FOUND, "The instrumental is no longer cached")
            return
        size = path.stat().st_size
        start, end = 0, size - 1
        status = HTTPStatus.OK
        raw_range = self.headers.get("Range")
        if raw_range:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", raw_range.strip())
            if not match:
                self.send_error(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                return
            first, last = match.groups()
            if not first:
                length = min(int(last), size)
                start = size - length
            else:
                start = int(first)
                end = min(int(last), size - 1) if last else size - 1
            if start < 0 or start >= size or end < start:
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            status = HTTPStatus.PARTIAL_CONTENT

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "audio/flac")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "private, max-age=3600")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        with path.open("rb") as source:
            source.seek(start)
            remaining = length
            while remaining:
                block = source.read(min(1024 * 1024, remaining))
                if not block:
                    break
                self.wfile.write(block)
                remaining -= len(block)
        with JOBS_LOCK:
            job = JOBS.get(item_id)
            if job:
                job.last_accessed = int(time.time())
                persist(job)

    def stream_path(self, path: Path) -> None:
        size = path.stat().st_size
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(size))
        # HTTP headers are latin-1 in BaseHTTPRequestHandler. RFC 5987 keeps
        # Unicode artist/title punctuation ASCII-safe on the wire.
        encoded_name = urllib.parse.quote(path.name, safe="")
        self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{encoded_name}")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with path.open("rb") as source:
            shutil.copyfileobj(source, self.wfile, length=1024 * 1024)


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    load_jobs()
    enhanced_lrc_gpu_api.initialise()
    threading.Thread(target=worker, name="demucs-worker", daemon=True).start()
    enhanced_lrc.start_worker()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    LOG.info("Listening on :%d, Jellyfin=%s, model=%s", PORT, JELLYFIN_URL, MODEL)
    server.serve_forever()


if __name__ == "__main__":
    main()
