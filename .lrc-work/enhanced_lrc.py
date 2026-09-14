#!/usr/bin/env python3
"""Resumable, validation-gated Enhanced LRC worker for Accord.

The audio tree is mounted read-only at MEDIA_ROOT. Only validated .lrc sidecars are
written through LYRICS_ROOT, which is a second mount of the same tree. State and
backups live outside the media library in CACHE_ROOT.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import logging
import math
import os
import re
import shutil
import sqlite3
import subprocess
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterable

# The alignment rules, shared with the desktop app and the batch runner. This file is a
# verbatim copy of the canonical module in Bananz0/fincord-lyrics-studio - do not edit it
# here. Three copies of these rules had already drifted into three different opinions
# about the same bug, which is what folding them into one module ended.
from elrc_rules import (  # noqa: E402
    MAX_INTERIOR_HOLD_MS,
    PLAUSIBLE_BASE_S,
    PLAUSIBLE_PER_WORD_S,
    SHORT_LINE_WORDS,
    stamp_seconds as stamp,
    stretched_word_in_rendered,
    window_for,
)


LOG = logging.getLogger("accord-enhanced-lrc")
MEDIA_ROOT = Path(os.environ.get("MEDIA_ROOT", "/data/media")).resolve()
LYRICS_ROOT = Path(os.environ.get("LRC_WRITE_ROOT", "/lyrics-target")).resolve()
CACHE_ROOT = Path(os.environ.get("CACHE_ROOT", "/cache")).resolve() / "enhanced-lrc"
DB_PATH = CACHE_ROOT / "queue.sqlite3"
BACKUP_ROOT = CACHE_ROOT / "backups"
LRCLIB_CACHE_ROOT = CACHE_ROOT / "lrclib-cache"
LRCLIB_URL = os.environ.get("LRCLIB_URL", "https://lrclib.net").rstrip("/")
JELLYFIN_URL = os.environ.get("JELLYFIN_URL", "http://127.0.0.1:8096").rstrip("/")
JELLYFIN_AUTH_FILE = Path(os.environ.get("JELLYFIN_AUTH_FILE", "/cache/.jellyfin-authorization"))
REFRESH_SECONDS = max(60, int(os.environ.get("LRC_REFRESH_SECONDS", "600")))
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small")
WHISPER_COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "int8")
WHISPER_DOWNLOAD_ROOT = os.environ.get("WHISPER_DOWNLOAD_ROOT", "/models/whisper")
LRC_DEVICE = os.environ.get("LRC_DEVICE", "cpu").casefold()
LRC_DEVICE_INDEX = int(os.environ.get("LRC_DEVICE_INDEX", "0"))
POLL_SECONDS = max(2, int(os.environ.get("LRC_POLL_SECONDS", "10")))
MAX_ATTEMPTS = max(1, int(os.environ.get("LRC_MAX_ATTEMPTS", "3")))
WEBHOOK_SECRET_FILE = Path(os.environ.get("LRC_WEBHOOK_SECRET_FILE", "/cache/.lrc-webhook-secret"))
SUPPORTED_AUDIO = {".aac", ".alac", ".flac", ".m4a", ".mp3", ".ogg", ".opus", ".wav", ".wma"}

LRC_LINE = re.compile(r"^\[(\d+):(\d{2})(?:[.:](\d+))?]\s*(.*)$")
WORD_CUE = re.compile(r"<\d+:\d{2}(?:[.:]\d{1,3})?>")
MIN_WORD_CUES = 5
LEASE_SECONDS = max(120, int(os.environ.get("LRC_LEASE_SECONDS", "900")))
HEARTBEAT_SECONDS = max(15, min(LEASE_SECONDS // 3, int(os.environ.get("LRC_HEARTBEAT_SECONDS", "60"))))
LRCLIB_RETRIES = max(1, int(os.environ.get("LRC_LRCLIB_RETRIES", "4")))
LRCLIB_CIRCUIT_SECONDS = max(30, int(os.environ.get("LRC_LRCLIB_CIRCUIT_SECONDS", "120")))
_MODEL_LOCK = threading.Lock()
_ALIGNER: tuple[Any, Any, dict[str, int], int, set[str], int, Any] | None = None
_WHISPER: Any | None = None
_STOP = threading.Event()
_LRCLIB_LOCK = threading.Lock()
_LRCLIB_FAILURES = 0
_LRCLIB_OPEN_UNTIL = 0.0


class PermanentSkip(RuntimeError):
    """A track cannot currently be enhanced and should not be retried automatically."""


class ExistingEnhanced(PermanentSkip):
    """The track already has an Enhanced LRC and must never be overwritten."""


class NeedsReview(RuntimeError):
    """Generated timing did not pass the conservative installation gate."""


class TransientSourceError(RuntimeError):
    """An upstream lyric service is temporarily unavailable; do not spend a job attempt."""


def db() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH, timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout=30000")
    connection.execute("PRAGMA journal_mode=WAL")
    return connection


def initialise() -> None:
    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    LRCLIB_CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    with db() as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                path TEXT PRIMARY KEY,
                fingerprint TEXT,
                status TEXT NOT NULL,
                source TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                reason TEXT,
                message TEXT,
                output_path TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        connection.execute("CREATE INDEX IF NOT EXISTS jobs_status_idx ON jobs(status, updated_at)")
        columns = {row["name"] for row in connection.execute("PRAGMA table_info(jobs)")}
        if "leased_by" not in columns:
            connection.execute("ALTER TABLE jobs ADD COLUMN leased_by TEXT")
        if "leased_until" not in columns:
            connection.execute("ALTER TABLE jobs ADD COLUMN leased_until INTEGER")
        if "required_lane" not in columns:
            connection.execute("ALTER TABLE jobs ADD COLUMN required_lane TEXT")
        recover_expired_leases(connection)


def recover_expired_leases(connection: sqlite3.Connection) -> int:
    """Return abandoned CPU/GPU work to the queue after its heartbeat expires."""
    now = int(time.time())
    cursor = connection.execute(
        "UPDATE jobs SET status='queued', leased_by=NULL, leased_until=NULL, "
        "message='Expired worker lease recovered', updated_at=? "
        "WHERE status IN ('processing','leased') AND COALESCE(leased_until,0) < ?",
        (now, now),
    )
    return cursor.rowcount


def is_below(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def source_path(raw: str | Path) -> Path:
    path = Path(raw)
    if not path.is_absolute():
        raise ValueError("Media paths must be absolute")
    resolved = path.resolve(strict=False)
    if not is_below(resolved, MEDIA_ROOT):
        raise ValueError(f"Path is outside {MEDIA_ROOT}")
    return resolved


def writable_audio_path(source: Path) -> Path:
    relative = source.relative_to(MEDIA_ROOT)
    target = (LYRICS_ROOT / relative).resolve(strict=False)
    if not is_below(target, LYRICS_ROOT):
        raise ValueError("Resolved lyric target escaped the write root")
    return target


def iter_audio(paths: Iterable[str | Path], limit: int | None = None) -> Iterable[Path]:
    yielded = 0
    seen: set[Path] = set()
    for raw in paths:
        try:
            path = source_path(raw)
        except (ValueError, OSError) as exc:
            LOG.warning("Ignoring unsafe enqueue path %s: %s", raw, exc)
            continue
        candidates = [path] if path.is_file() else path.rglob("*") if path.is_dir() else []
        for candidate in candidates:
            if candidate in seen or not candidate.is_file() or candidate.suffix.casefold() not in SUPPORTED_AUDIO:
                continue
            seen.add(candidate)
            yield candidate
            yielded += 1
            if limit is not None and yielded >= limit:
                return


def fingerprint(path: Path) -> str:
    stat = path.stat()
    return hashlib.sha256(f"{path}:{stat.st_size}:{stat.st_mtime_ns}".encode()).hexdigest()[:24]


def enqueue(paths: Iterable[str | Path], *, reason: str, limit: int | None = None) -> dict[str, int]:
    now = int(time.time())
    added = existing = 0
    with db() as connection:
        for path in iter_audio(paths, limit):
            current_fingerprint = fingerprint(path)
            row = connection.execute("SELECT fingerprint, status FROM jobs WHERE path=?", (str(path),)).fetchone()
            if row and row["fingerprint"] == current_fingerprint and row["status"] in {"complete", "queued", "processing", "leased"}:
                existing += 1
                continue
            connection.execute(
                """
                INSERT INTO jobs(path, fingerprint, status, attempts, reason, message, created_at, updated_at)
                VALUES(?, ?, 'queued', 0, ?, NULL, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    fingerprint=excluded.fingerprint, status='queued', attempts=0,
                    required_lane=NULL, reason=excluded.reason, message=NULL, updated_at=excluded.updated_at
                """,
                (str(path), current_fingerprint, reason[:100], now, now),
            )
            added += 1
    return {"added": added, "unchanged": existing}


def claim_job(worker: str) -> sqlite3.Row | None:
    connection = db()
    try:
        connection.execute("BEGIN IMMEDIATE")
        recover_expired_leases(connection)
        row = connection.execute(
            "SELECT * FROM jobs WHERE status='queued' AND attempts < ? "
            "AND (required_lane IS NULL OR required_lane='cpu') ORDER BY updated_at, path LIMIT 1",
            (MAX_ATTEMPTS,),
        ).fetchone()
        if row is None:
            connection.commit()
            return None
        connection.execute(
            "UPDATE jobs SET status='processing', attempts=attempts+1, leased_by=?, leased_until=?, updated_at=? "
            "WHERE path=? AND status='queued'",
            (worker, int(time.time()) + LEASE_SECONDS, int(time.time()), row["path"]),
        )
        connection.commit()
        return row
    finally:
        connection.close()


def heartbeat(path: Path, worker: str) -> bool:
    now = int(time.time())
    with db() as connection:
        cursor = connection.execute(
            "UPDATE jobs SET leased_until=?, updated_at=? WHERE path=? AND leased_by=? "
            "AND status IN ('processing','leased')",
            (now + LEASE_SECONDS, now, str(path), worker),
        )
        return cursor.rowcount == 1


@contextmanager
def lease_heartbeat(path: Path, worker: str):
    stop = threading.Event()

    def beat() -> None:
        while not stop.wait(HEARTBEAT_SECONDS):
            try:
                if not heartbeat(path, worker):
                    LOG.warning("Lost queue lease for %s", path)
                    return
            except Exception:
                LOG.exception("Could not heartbeat queue lease for %s", path)

    thread = threading.Thread(target=beat, name=f"lrc-lease-{worker}", daemon=True)
    thread.start()
    try:
        yield
    finally:
        stop.set()
        thread.join(timeout=2)


def finish(path: Path, status: str, message: str, *, source: str | None = None, output: Path | None = None) -> None:
    current_fingerprint = fingerprint(path) if status == "complete" and path.is_file() else None
    with db() as connection:
        connection.execute(
            "UPDATE jobs SET status=?, message=?, source=COALESCE(?, source), "
            "output_path=COALESCE(?, output_path), fingerprint=COALESCE(?, fingerprint), "
            "leased_by=NULL, leased_until=NULL, updated_at=? WHERE path=?",
            (status, message[-1000:], source, str(output) if output else None,
             current_fingerprint, int(time.time()), str(path)),
        )


def parse_lrc(value: str) -> list[tuple[float, str]]:
    lines: list[tuple[float, str]] = []
    for raw in value.splitlines():
        match = LRC_LINE.match(raw.strip())
        if not match:
            continue
        fraction = match.group(3) or "0"
        seconds = int(match.group(1)) * 60 + int(match.group(2)) + int(fraction) / (10 ** len(fraction))
        text = WORD_CUE.sub("", match.group(4)).strip()
        if text:
            lines.append((seconds, text))
    # Avoid duplicate timestamp variants and malformed out-of-order source files.
    return sorted(dict(((round(start, 3), text), (start, text)) for start, text in lines).values())


def is_enhanced(value: str) -> bool:
    return len(WORD_CUE.findall(value)) >= MIN_WORD_CUES


def probe(path: Path) -> tuple[dict[str, str], float]:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags", "-of", "json", str(path)],
        check=True, capture_output=True, text=True, timeout=90,
    )
    data = json.loads(result.stdout).get("format", {})
    tags = {str(k).casefold(): str(v) for k, v in data.get("tags", {}).items()}
    return tags, float(data.get("duration") or 0)


def sidecar_path(path: Path) -> Path:
    return writable_audio_path(path).with_suffix(".lrc")


def embedded_word_cues(path: Path) -> int:
    """Re-read the audio tag and count word markers which survived serialization."""
    suffix = path.suffix.casefold()
    try:
        if suffix == ".mp3":
            from mutagen.id3 import ID3
            value = "".join(str(frame.text) for frame in ID3(path).getall("USLT"))
        elif suffix == ".flac":
            from mutagen.flac import FLAC
            value = "".join(FLAC(path).get("LYRICS", []))
        elif suffix == ".ogg":
            from mutagen.oggvorbis import OggVorbis
            value = "".join(OggVorbis(path).get("LYRICS", []))
        elif suffix == ".opus":
            from mutagen.oggopus import OggOpus
            value = "".join(OggOpus(path).get("LYRICS", []))
        elif suffix == ".m4a":
            from mutagen.mp4 import MP4
            value = "".join(MP4(path).get("\xa9lyr", []))
        else:
            return 0
    except Exception:
        return 0
    return len(WORD_CUE.findall(value))


def embed_enhanced(path: Path, content: str) -> int:
    """Embed validated Enhanced LRC while preserving the audio file's original timestamps."""
    markers = len(WORD_CUE.findall(content))
    if markers < MIN_WORD_CUES:
        raise ValueError(f"Refusing to embed a sidecar with only {markers} word markers")
    target = writable_audio_path(path)
    before = target.stat()
    suffix = target.suffix.casefold()
    try:
        if suffix == ".mp3":
            from mutagen.id3 import ID3, ID3NoHeaderError, USLT
            from mutagen.mp3 import MP3
            try:
                tags = ID3(target)
            except ID3NoHeaderError:
                audio = MP3(target)
                audio.add_tags()
                tags = audio.tags
            for key in list(tags.keys()):
                if key.upper().startswith(("USLT", "SYLT")):
                    del tags[key]
            tags.add(USLT(encoding=3, lang="eng", desc="", text=content))
            tags.save(target, v2_version=3)
        elif suffix == ".flac":
            from mutagen.flac import FLAC
            audio = FLAC(target)
            for key in list(audio.keys()):
                if key.casefold() in {"lyrics", "unsyncedlyrics", "syncedlyrics"}:
                    del audio[key]
            audio["LYRICS"] = content
            audio.save()
        elif suffix in {".ogg", ".opus"}:
            if suffix == ".opus":
                from mutagen.oggopus import OggOpus as OggAudio
            else:
                from mutagen.oggvorbis import OggVorbis as OggAudio
            audio = OggAudio(target)
            for key in list(audio.keys()):
                if key.casefold() in {"lyrics", "unsyncedlyrics", "syncedlyrics"}:
                    del audio[key]
            audio["LYRICS"] = content
            audio.save()
        elif suffix == ".m4a":
            from mutagen.mp4 import MP4
            audio = MP4(target)
            audio["\xa9lyr"] = [content]
            audio.save()
        else:
            raise PermanentSkip(f"Embedding is unsupported for {suffix or 'this container'}")
    finally:
        # Taggers commonly rewrite the whole container. Preserve both timestamps so neither
        # Jellyfin nor Lidarr mistakes a metadata-only write for newly imported media.
        os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
    survived = embedded_word_cues(target)
    if survived < MIN_WORD_CUES:
        raise RuntimeError(f"Embedded lyric verification failed: only {survived} word markers survived")
    return survived


def filesystem_complete(path: Path) -> bool:
    sidecar = sidecar_path(path)
    if not sidecar.is_file():
        return False
    try:
        content = sidecar.read_text(encoding="utf-8-sig", errors="replace")
    except OSError:
        return False
    return is_enhanced(content) and embedded_word_cues(writable_audio_path(path)) >= MIN_WORD_CUES


def lrclib(tags: dict[str, str], duration: float) -> dict[str, Any]:
    global _LRCLIB_FAILURES, _LRCLIB_OPEN_UNTIL
    artist = tags.get("album_artist") or tags.get("albumartist") or tags.get("artist")
    title = tags.get("title")
    if not artist or not title or duration <= 0:
        return {}
    query = {
        "artist_name": artist,
        "track_name": title,
        "duration": str(round(duration)),
    }
    album = tags.get("album")
    if album:
        query["album_name"] = album
    url = f"{LRCLIB_URL}/api/get?{urllib.parse.urlencode(query)}"
    cache_key = hashlib.sha256(url.encode()).hexdigest()
    cache_path = LRCLIB_CACHE_ROOT / f"{cache_key}.json"
    if cache_path.is_file():
        try:
            cached = json.loads(cache_path.read_text(encoding="utf-8"))
            if isinstance(cached, dict):
                return cached
        except (OSError, json.JSONDecodeError):
            cache_path.unlink(missing_ok=True)
    with _LRCLIB_LOCK:
        if time.monotonic() < _LRCLIB_OPEN_UNTIL:
            raise TransientSourceError("LRCLIB circuit is open after repeated transient failures")
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "AccordEnhancedLRC/1.0 (private media server)"},
    )
    transient_codes = {429, 500, 502, 503, 504}
    for attempt in range(1, LRCLIB_RETRIES + 1):
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                payload = json.load(response)
            result = payload if isinstance(payload, dict) else {}
            with _LRCLIB_LOCK:
                _LRCLIB_FAILURES = 0
                _LRCLIB_OPEN_UNTIL = 0.0
            if result:
                temporary = cache_path.with_suffix(f".{os.getpid()}.tmp")
                temporary.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                os.replace(temporary, cache_path)
            return result
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return {}
            if exc.code not in transient_codes:
                raise
            retry_after = exc.headers.get("Retry-After") if exc.headers else None
            detail = f"HTTP {exc.code}"
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            retry_after = None
            detail = f"{type(exc).__name__}: {exc}"
        if attempt < LRCLIB_RETRIES:
            try:
                delay = min(60.0, float(retry_after)) if retry_after else min(30.0, 2.0 ** attempt)
            except ValueError:
                delay = min(30.0, 2.0 ** attempt)
            # Stable sub-second jitter prevents all workers reopening in lockstep.
            delay += int(cache_key[:4], 16) % 1000 / 1000.0
            LOG.warning("LRCLIB transient failure (%s); retry %d/%d in %.1fs", detail, attempt, LRCLIB_RETRIES, delay)
            _STOP.wait(delay)
    with _LRCLIB_LOCK:
        _LRCLIB_FAILURES += 1
        if _LRCLIB_FAILURES >= 2:
            _LRCLIB_OPEN_UNTIL = time.monotonic() + LRCLIB_CIRCUIT_SECONDS
    raise TransientSourceError(f"LRCLIB unavailable after {LRCLIB_RETRIES} attempts ({detail})")


def lyric_source(path: Path, tags: dict[str, str], duration: float) -> tuple[str, list[tuple[float, str]]]:
    sidecar = path.with_suffix(".lrc")
    if sidecar.is_file():
        value = sidecar.read_text(encoding="utf-8-sig", errors="replace")
        if is_enhanced(value):
            raise ExistingEnhanced("A valid-looking Enhanced LRC sidecar already exists")
        lines = parse_lrc(value)
        if lines:
            return "sidecar", lines
    for key in ("syncedlyrics", "lyrics", "unsyncedlyrics"):
        value = tags.get(key, "")
        lines = parse_lrc(value)
        if lines:
            return f"embedded:{key}", lines
    payload = lrclib(tags, duration)
    value = str(payload.get("syncedLyrics") or "")
    tags["_lrclib_plain"] = str(payload.get("plainLyrics") or "")
    lines = parse_lrc(value)
    if lines:
        return "lrclib", lines
    raise PermanentSkip("No line-synchronized lyrics were found")


def plain_lyric_lines(path: Path, tags: dict[str, str]) -> list[str]:
    candidates: list[str] = []
    sidecar = path.with_suffix(".lrc")
    if sidecar.is_file():
        candidates.append(sidecar.read_text(encoding="utf-8-sig", errors="replace"))
    candidates.extend(tags.get(key, "") for key in ("unsyncedlyrics", "lyrics", "_lrclib_plain"))
    for value in candidates:
        cleaned: list[str] = []
        for raw in value.splitlines():
            line = LRC_LINE.sub(lambda match: match.group(4), raw).strip()
            line = WORD_CUE.sub("", line).strip()
            if line and not re.fullmatch(r"\[.*]", line):
                cleaned.append(line)
        if len(" ".join(cleaned).split()) >= 4:
            return cleaned
    return []


def normalise_word(word: str, labels: set[str]) -> str:
    word = word.replace("’", "'").replace("‘", "'").casefold()
    if word == "&":
        word = "and"
    word = "".join(ch for ch in unicodedata.normalize("NFKD", word) if not unicodedata.combining(ch))
    return "".join(ch for ch in word.upper() if ch in labels)



def evenly_spaced(start: float, end: float, count: int) -> list[tuple[float, float]]:
    if count == 0:
        return []
    step = max(0.12 * count, end - start) / count
    return [(start + i * step, start + (i + 0.9) * step) for i in range(count)]


def get_aligner() -> tuple[Any, Any, dict[str, int], int, set[str], int, Any]:
    global _ALIGNER
    with _MODEL_LOCK:
        if _ALIGNER is None:
            import torch
            import torchaudio

            torch.set_num_threads(max(1, int(os.environ.get("LRC_TORCH_THREADS", "2"))))
            bundle = torchaudio.pipelines.WAV2VEC2_ASR_BASE_960H
            device = torch.device(f"cuda:{LRC_DEVICE_INDEX}" if LRC_DEVICE == "cuda" else "cpu")
            model = bundle.get_model().to(device).eval()
            dictionary = {character: index for index, character in enumerate(bundle.get_labels())}
            labels = set(bundle.get_labels()) - {"-", "|"}
            _ALIGNER = torch, model, dictionary, dictionary["-"], labels, bundle.sample_rate, device
        return _ALIGNER


def get_whisper() -> Any:
    global _WHISPER
    with _MODEL_LOCK:
        if _WHISPER is None:
            from faster_whisper import WhisperModel

            Path(WHISPER_DOWNLOAD_ROOT).mkdir(parents=True, exist_ok=True)
            _WHISPER = WhisperModel(
                WHISPER_MODEL,
                device="cuda" if LRC_DEVICE == "cuda" else "cpu",
                device_index=LRC_DEVICE_INDEX,
                compute_type=WHISPER_COMPUTE_TYPE,
                download_root=WHISPER_DOWNLOAD_ROOT,
                cpu_threads=max(1, int(os.environ.get("LRC_TORCH_THREADS", "2"))),
                num_workers=1,
            )
        return _WHISPER


def canonical_whisper_lines(
    recognised: list[tuple[float, float, str]], canonical_lines: list[str], duration: float
) -> tuple[list[str], float]:
    canonical: list[tuple[int, str]] = [
        (line_index, word)
        for line_index, line in enumerate(canonical_lines)
        for word in line.split()
    ]
    if len(canonical) < 8:
        raise NeedsReview("Canonical plain lyrics contain too few words")

    def key(word: str) -> str:
        value = "".join(
            ch for ch in unicodedata.normalize("NFKD", word.casefold())
            if not unicodedata.combining(ch)
        )
        return re.sub(r"[^\w']", "", value)

    canonical_keys = [key(word) for _, word in canonical]
    recognised_keys = [key(word) for _, _, word in recognised]
    matcher = difflib.SequenceMatcher(None, canonical_keys, recognised_keys, autojunk=False)
    similarity = matcher.ratio()
    if similarity < 0.35:
        raise NeedsReview(f"Whisper transcript did not match canonical lyrics ({similarity:.2f})")

    timings: list[tuple[float, float] | None] = [None] * len(canonical)
    largest_unheard_block = 0
    for tag, first_canonical, last_canonical, first_asr, last_asr in matcher.get_opcodes():
        count = last_canonical - first_canonical
        if tag == "insert" or count == 0:
            continue
        if tag == "equal":
            for offset in range(count):
                timings[first_canonical + offset] = recognised[first_asr + offset][:2]
            continue
        if first_asr < last_asr:
            begin = recognised[first_asr][0]
            end = recognised[last_asr - 1][1]
        else:
            largest_unheard_block = max(largest_unheard_block, count)
            begin = recognised[first_asr - 1][1] if first_asr else 0.0
            end = recognised[first_asr][0] if first_asr < len(recognised) else duration
        end = max(end, begin + 0.08 * count)
        step = (end - begin) / count
        for offset in range(count):
            start = begin + offset * step
            timings[first_canonical + offset] = (start, start + max(0.04, step * 0.9))
    if largest_unheard_block > 12:
        raise NeedsReview(f"Whisper missed a canonical block of {largest_unheard_block} words")

    rendered: list[str] = []
    previous_end = -1.0
    position = 0
    for line_index, line in enumerate(canonical_lines):
        words = line.split()
        if not words:
            continue
        line_timings: list[tuple[float, float]] = []
        for _ in words:
            timing = timings[position]
            if timing is None:
                raise NeedsReview("Canonical lyric mapping left an untimed word")
            start = max(timing[0], previous_end + 0.01)
            end = max(start + 0.04, timing[1])
            end = min(end, duration + 2)
            line_timings.append((start, end))
            previous_end = end
            position += 1
        pieces = [stamp(line_timings[index][0]) + word for index, word in enumerate(words)]
        rendered.append(stamp(line_timings[0][0], "[]") + " " + " ".join(pieces) + stamp(line_timings[-1][1]))
    return rendered, similarity


def whisper_track(
    path: Path, duration: float, canonical_lines: list[str] | None = None
) -> tuple[list[str], int, str, bool]:
    model = get_whisper()
    prompt = " ".join(canonical_lines or [])[:4000]
    segments, info = model.transcribe(
        str(path),
        beam_size=5,
        word_timestamps=True,
        vad_filter=False,
        condition_on_previous_text=False,
        initial_prompt=prompt or None,
    )
    rendered: list[str] = []
    recognised: list[tuple[float, float, str]] = []
    probabilities: list[float] = []
    tokens: list[str] = []
    previous_end = -1.0
    for segment in segments:
        words: list[tuple[float, float, str]] = []
        for word in segment.words or []:
            text = str(word.word or "").strip()
            if not text or word.start is None or word.end is None:
                continue
            start = max(float(word.start), previous_end + 0.01)
            end = max(start + 0.04, float(word.end))
            if start > duration + 2:
                continue
            words.append((start, end, text))
            recognised.append((start, end, text))
            previous_end = end
            probabilities.append(float(word.probability or 0))
            normal = re.sub(r"[^\w']", "", text.casefold())
            if normal:
                tokens.append(normal)
        if not words:
            continue
        pieces = [stamp(start) + text for start, _, text in words]
        rendered.append(stamp(words[0][0], "[]") + " " + " ".join(pieces) + stamp(words[-1][1]))

    recognised_count = len(probabilities)
    if recognised_count < 8:
        raise NeedsReview("Whisper produced too few timestamped words")
    average_probability = sum(probabilities) / recognised_count
    language_probability = float(getattr(info, "language_probability", 0) or 0)
    unique_ratio = len(set(tokens)) / len(tokens) if tokens else 0
    canonical_used = bool(canonical_lines)
    minimum_word_probability = 0.45 if canonical_used else 0.70
    minimum_language_probability = 0.45 if canonical_used else 0.70
    if average_probability < minimum_word_probability:
        raise NeedsReview(f"Whisper word confidence was too low ({average_probability:.2f})")
    if language_probability < minimum_language_probability:
        raise NeedsReview(f"Whisper language confidence was too low ({language_probability:.2f})")
    if recognised_count >= 40 and unique_ratio < 0.12:
        raise NeedsReview("Whisper output appears repetitious or hallucinatory")
    if canonical_lines:
        rendered, similarity = canonical_whisper_lines(recognised, canonical_lines, duration)
        word_count = sum(len(line.split()) for line in canonical_lines)
        LOG.info("Canonical lyric/Whisper transcript similarity for %s: %.2f", path.name, similarity)
    else:
        word_count = recognised_count
    words_per_minute = word_count / max(duration / 60, 0.1)
    if words_per_minute < 2 or words_per_minute > 450:
        raise NeedsReview(f"Whisper word rate was implausible ({words_per_minute:.0f}/min)")
    language = str(getattr(info, "language", "unknown") or "unknown")
    LOG.info(
        "Whisper fallback for %s: language=%s language_probability=%.2f word_probability=%.2f",
        path.name, language, language_probability, average_probability,
    )
    return rendered, word_count, language, canonical_used


def align_track(path: Path, lines: list[tuple[float, str]], duration: float) -> tuple[list[str], int, int]:
    torch, model, dictionary, blank, labels, sample_rate, device = get_aligner()
    import torchaudio

    # Decode through the FFmpeg CLI instead of torchaudio's optional media
    # backends. This keeps decoding independent of the host FFmpeg ABI while
    # preserving torchaudio for CUDA-backed CTC forced alignment below.
    decoded = subprocess.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-i", str(path), "-vn", "-ac", "1", "-ar", str(sample_rate),
            "-f", "f32le", "pipe:1",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=max(900, int(duration * 2 + 120)),
    )
    if decoded.returncode != 0:
        detail = decoded.stderr.decode("utf-8", errors="replace").strip()[-1000:]
        raise RuntimeError(f"FFmpeg could not decode {path.name}: {detail}")
    if not decoded.stdout or len(decoded.stdout) % 4:
        raise RuntimeError(f"FFmpeg returned invalid float audio for {path.name}")
    waveform = torch.frombuffer(bytearray(decoded.stdout), dtype=torch.float32).unsqueeze(0)
    actual_duration = waveform.shape[1] / sample_rate
    if duration <= 0:
        duration = actual_duration

    total_words = sum(len(text.split()) for _, text in lines)
    supported_words = sum(bool(normalise_word(word, labels)) for _, text in lines for word in text.split())
    if total_words < 4:
        raise PermanentSkip("Too little lyric text to align")
    if supported_words / total_words < 0.75:
        raise PermanentSkip("Lyrics use a language/script unsupported by the current alignment model")

    rendered: list[str] = []
    aligned_words = fallback_words = 0
    previous_rendered_end = -1.0
    for index, (line_start, original_text) in enumerate(lines):
        next_start = lines[index + 1][0] if index + 1 < len(lines) else duration
        if line_start >= duration + 1 or next_start <= line_start:
            continue
        original_words = original_text.split()
        normalised = [normalise_word(word, labels) for word in original_words]
        active = [(i, word) for i, word in enumerate(normalised) if word]
        if not active:
            continue
        window_start, window_end = window_for(line_start, len(active),
                                              next_start, duration)
        # Bounded by what the line could plausibly take to sing, not by where the next
        # line starts.
        #
        # Running the window to next_start assumes CTC will leave an instrumental gap as
        # blank frames. It does not, reliably: where the acoustic evidence for a word is
        # weak the aligner places it anywhere in the window that scores best. Given the
        # eleven seconds between two lines of "SHE DID IT AGAIN" it produced
        #
        #     [00:01.47] <00:01.47>I <00:02.94>know <00:11.54>you <00:12.74>do<00:12.78>
        #
        # for a phrase sung inside three, holding `know` for 8.6 s and lighting `you do`
        # as the next line began. A library scan found the same shape in 12,511 short
        # lines across 5,428 of 9,710 files.
        #
        # Two thirds of a second a word plus slack is generous for sung delivery and an
        # order of magnitude tighter than an instrumental.
        # Still needed on its own for the widened retry below.
        plausible = line_start + PLAUSIBLE_BASE_S + PLAUSIBLE_PER_WORD_S * len(active)
        timings: list[tuple[float, float] | None] = [None] * len(original_words)

        def run_alignment(begin: float, end: float) -> list[tuple[float, float]]:
            sample_start = max(0, math.floor(begin * sample_rate))
            sample_end = min(waveform.shape[1], math.ceil(end * sample_rate))
            segment = waveform[:, sample_start:sample_end].to(device)
            with torch.inference_mode():
                emission, _ = model(segment)
            emission = emission[0]
            seconds_per_frame = segment.shape[1] / sample_rate / emission.shape[0]
            words = [word for _, word in active]
            transcript = "|".join(words)
            targets = torch.tensor([[dictionary[ch] for ch in transcript]], dtype=torch.int32, device=device)
            tokens, scores = torchaudio.functional.forced_align(
                torch.log_softmax(emission, dim=-1).unsqueeze(0), targets, blank=blank
            )
            spans = torchaudio.functional.merge_tokens(tokens[0].cpu(), scores[0].cpu(), blank=blank)
            span_index = 0
            result: list[tuple[float, float]] = []
            for word_index, word in enumerate(words):
                current = spans[span_index:span_index + len(word)]
                if len(current) != len(word):
                    raise RuntimeError("CTC alignment returned incomplete character spans")
                result.append((begin + current[0].start * seconds_per_frame, begin + current[-1].end * seconds_per_frame))
                span_index += len(word) + (1 if word_index + 1 < len(words) else 0)
            return result

        try:
            try:
                word_timings = run_alignment(window_start, window_end)
            except Exception:
                # The retry widens the window a little, but still may not run to the
                # next line - that is the failure being fixed, not a fallback from it.
                retry_end = min(duration, max(line_start + 1,
                                              min(next_start + 1.5, plausible + 1.5)))
                word_timings = run_alignment(max(0, line_start - 1.5), retry_end)
            for (original_index, _), timing in zip(active, word_timings):
                timings[original_index] = timing
                aligned_words += 1
        except Exception as exc:
            LOG.warning("Fallback %s line %d: %s", path.name, index + 1, exc)
            fallback = evenly_spaced(line_start, max(line_start + 0.5, next_start), len(active))
            for (original_index, _), timing in zip(active, fallback):
                timings[original_index] = timing
                fallback_words += 1

        previous = line_start
        for word_index, timing in enumerate(timings):
            if timing is None:
                timings[word_index] = (previous, previous + 0.08)
            previous = max(previous, timings[word_index][0])
        starts: list[float] = []
        cursor = max(0, line_start - 0.1, previous_rendered_end + 0.01)
        for timing in timings:
            current = max(cursor, timing[0])
            starts.append(current)
            cursor = current + 0.01
        # A line ends where its last word ends. It used to be clamped towards the next
        # line's start, which stretches every line across whatever silence follows it and
        # is the second half of the same defect: Enhanced LRC infers a word's end from the
        # next marker, so a stretched line hands that silence to its final word.
        end_time = max(starts[-1] + 0.08, timings[-1][1])
        end_time = min(end_time, max(next_start - 0.01, starts[-1] + 0.04))
        pieces = [stamp(starts[i]) + word for i, word in enumerate(original_words)]
        rendered.append(stamp(starts[0], "[]") + " " + " ".join(pieces) + stamp(end_time))
        previous_rendered_end = end_time
    return rendered, aligned_words, fallback_words


def validate(rendered: list[str], duration: float, aligned: int, fallback: int) -> None:
    if not rendered or aligned < 4:
        raise NeedsReview("No usable aligned lyric lines were produced")
    cue_times: list[float] = []
    for line in rendered:
        for minutes, seconds, fraction in re.findall(r"<(\d+):(\d{2})[.:](\d{2,3})>", line):
            cue_times.append(int(minutes) * 60 + int(seconds) + int(fraction) / (10 ** len(fraction)))
    if len(cue_times) < aligned or any(b < a for a, b in zip(cue_times, cue_times[1:])):
        raise NeedsReview("Generated word cues are incomplete or non-monotonic")
    if cue_times[-1] > duration + 2:
        raise NeedsReview("Generated word cues extend past the audio duration")
    if fallback > max(3, math.floor((aligned + fallback) * 0.02)):
        raise NeedsReview(f"Fallback timing exceeded the safety threshold ({fallback} words)")
    stretched = stretched_word_in_rendered(rendered)
    if stretched:
        raise NeedsReview(stretched)


def install(path: Path, content: str) -> Path:
    target_audio = writable_audio_path(path)
    if not target_audio.is_file():
        raise FileNotFoundError("Writable view of the source audio is missing")
    target = target_audio.with_suffix(".lrc")
    if target.is_file():
        previous = target.read_text(encoding="utf-8-sig", errors="replace")
        if is_enhanced(previous):
            raise ExistingEnhanced("Enhanced LRC appeared while this track was processing")
        digest = hashlib.sha256(str(path).encode()).hexdigest()[:20]
        backup = BACKUP_ROOT / f"{digest}-{int(time.time())}.lrc"
        shutil.copy2(target, backup)
    temporary = target.with_name(f".{target.name}.accord-{os.getpid()}.tmp")
    temporary.write_text(content, encoding="utf-8")
    source_stat = target_audio.stat()
    os.chmod(temporary, 0o644)
    try:
        os.chown(temporary, source_stat.st_uid, source_stat.st_gid)
    except PermissionError:
        pass
    os.replace(temporary, target)
    (CACHE_ROOT / "refresh-needed").write_text(str(int(time.time())), encoding="ascii")
    return target


def reconcile() -> dict[str, int]:
    """Make queue state agree with sidecars and embedded tags on disk."""
    counts = {"complete": 0, "queued": 0, "missing": 0}
    now = int(time.time())
    # Never hold SQLite's writer lock while Mutagen walks thousands of files.
    # The independent Lidarr receiver must remain able to enqueue new imports.
    with db() as connection:
        rows = connection.execute("SELECT path,status,attempts FROM jobs").fetchall()
    def inspect(row: sqlite3.Row) -> tuple[str, str | None, str]:
        path = Path(row["path"])
        if not path.is_file():
            return "skipped", None, str(path)
        if filesystem_complete(path):
            return "complete", fingerprint(path), str(path)
        if row["status"] == "needs_review":
            return "needs_review", None, str(path)
        if int(row["attempts"]) >= MAX_ATTEMPTS:
            return "failed", None, str(path)
        return "queued", None, str(path)

    workers = max(1, min(8, int(os.environ.get("LRC_RECONCILE_WORKERS", "8"))))
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="lrc-reconcile") as executor:
        actions = list(executor.map(inspect, rows))
    for status, _, _ in actions:
        if status == "skipped":
            counts["missing"] += 1
        elif status == "complete":
            counts["complete"] += 1
        elif status == "queued":
            counts["queued"] += 1
    with db() as connection:
        for status, current_fingerprint, path in actions:
            if status == "skipped":
                connection.execute(
                    "UPDATE jobs SET status='skipped', leased_by=NULL, leased_until=NULL, "
                    "message='Audio file is missing after filesystem reconciliation', updated_at=? WHERE path=?",
                    (now, path),
                )
            elif status == "complete":
                connection.execute(
                    "UPDATE jobs SET status='complete', fingerprint=?, leased_by=NULL, leased_until=NULL, "
                    "message='Verified sidecar and embedded word tags during reconciliation', updated_at=? WHERE path=?",
                    (current_fingerprint, now, path),
                )
            elif status == "queued":
                connection.execute(
                    "UPDATE jobs SET status='queued', leased_by=NULL, leased_until=NULL, "
                    "message='Filesystem reconciliation requires processing or embedding', updated_at=? WHERE path=?",
                    (now, path),
                )
            elif status == "failed":
                connection.execute(
                    "UPDATE jobs SET status='failed', leased_by=NULL, leased_until=NULL, "
                    "message='Maximum attempts reached; filesystem verification still incomplete', updated_at=? WHERE path=?",
                    (now, path),
                )
    LOG.info("Filesystem reconciliation: %s", counts)
    return counts


def backfill(*, apply: bool, limit: int | None = None) -> dict[str, int]:
    """Embed existing Enhanced LRC sidecars without running alignment or Whisper."""
    counts = {
        "considered": 0, "already_verified": 0, "embedded": 0,
        "verified": 0, "line_level": 0, "no_audio": 0, "failed": 0,
    }
    supported = {".mp3", ".flac", ".m4a", ".ogg", ".opus"}
    reverse = os.environ.get("BACKFILL_REVERSE", "0").casefold() in {"1", "true", "yes"}
    for lrc_path in sorted(LYRICS_ROOT.rglob("*.lrc"), reverse=reverse):
        try:
            content = lrc_path.read_text(encoding="utf-8-sig", errors="replace")
        except OSError as exc:
            LOG.error("Could not read %s: %s", lrc_path, exc)
            counts["failed"] += 1
            continue
        if not is_enhanced(content):
            counts["line_level"] += 1
            continue
        target_audio = next(
            (lrc_path.with_suffix(suffix) for suffix in sorted(supported) if lrc_path.with_suffix(suffix).is_file()),
            None,
        )
        if target_audio is None:
            counts["no_audio"] += 1
            continue
        source_audio = MEDIA_ROOT / target_audio.relative_to(LYRICS_ROOT)
        counts["considered"] += 1
        if embedded_word_cues(target_audio) >= MIN_WORD_CUES:
            counts["already_verified"] += 1
        elif apply:
            try:
                embed_enhanced(source_audio, content)
                counts["embedded"] += 1
                counts["verified"] += 1
            except Exception as exc:
                counts["failed"] += 1
                LOG.error("Backfill failed for %s: %s", source_audio, exc)
                continue
        if apply and embedded_word_cues(target_audio) >= MIN_WORD_CUES:
            now = int(time.time())
            with db() as connection:
                connection.execute(
                    """
                    INSERT INTO jobs(path,fingerprint,status,source,attempts,reason,message,output_path,created_at,updated_at)
                    VALUES(?,?, 'complete','backfill',0,'backfill','Verified embedded word tags',?,?,?)
                    ON CONFLICT(path) DO UPDATE SET fingerprint=excluded.fingerprint,status='complete',
                    source='backfill',attempts=0,message='Verified embedded word tags',output_path=excluded.output_path,
                    leased_by=NULL,leased_until=NULL,updated_at=excluded.updated_at
                    """,
                    (str(source_audio), fingerprint(source_audio), str(lrc_path), now, now),
                )
        if limit and counts["considered"] >= limit:
            break
    return counts


def refresh_jellyfin() -> bool:
    marker = CACHE_ROOT / "refresh-needed"
    if not marker.is_file():
        return False
    authorization = JELLYFIN_AUTH_FILE.read_text(encoding="utf-8").strip()
    if not authorization:
        raise RuntimeError("Jellyfin authorization file is empty")
    request = urllib.request.Request(
        f"{JELLYFIN_URL}/Library/Refresh",
        method="POST",
        headers={"Authorization": authorization, "Content-Length": "0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status not in {200, 204}:
            raise RuntimeError(f"Jellyfin refresh returned HTTP {response.status}")
    marker.unlink(missing_ok=True)
    LOG.info("Requested debounced Jellyfin library refresh")
    return True


def refresh_loop() -> None:
    while not _STOP.wait(REFRESH_SECONDS):
        try:
            refresh_jellyfin()
        except Exception:
            LOG.exception("Jellyfin refresh failed; leaving the refresh marker for retry")


def generate_content(
    path: Path,
    tags: dict[str, str],
    duration: float,
    source: str,
    lines: list[tuple[float, str]],
    canonical_lines: list[str],
) -> tuple[str, str, int, int]:
    if lines:
        alignment_lines = [text for _, text in lines]
        try:
            rendered, aligned, fallback = align_track(path, lines, duration)
            validate(rendered, duration, aligned, fallback)
        except (PermanentSkip, NeedsReview):
            rendered, aligned, language, canonical_used = whisper_track(path, duration, alignment_lines)
            fallback = 0
            source = f"whisper:{language}:{'canonical' if canonical_used else 'transcription'}"
    else:
        rendered, aligned, language, canonical_used = whisper_track(path, duration, canonical_lines)
        fallback = 0
        source = f"whisper:{language}:{'canonical' if canonical_used else 'transcription'}"
    validate(rendered, duration, aligned, fallback)
    header = [
        f"[ar:{tags.get('artist', '')}]",
        f"[al:{tags.get('album', '')}]",
        f"[ti:{tags.get('title', path.stem)}]",
        f"[by:Accord Enhanced LRC ({source})]",
    ]
    return "\n".join(header + rendered) + "\n", source, aligned, fallback


def process(path: Path) -> None:
    existing = sidecar_path(path)
    if existing.is_file():
        existing_content = existing.read_text(encoding="utf-8-sig", errors="replace")
        if is_enhanced(existing_content):
            markers = embed_enhanced(path, existing_content)
            finish(path, "complete", f"Embedded and verified existing Enhanced LRC ({markers} markers)",
                   source="sidecar-backfill", output=existing)
            LOG.info("Embedded existing Enhanced LRC for %s (%d markers)", path, markers)
            return
    tags, duration = probe(path)
    try:
        source, lines = lyric_source(path, tags, duration)
        canonical_lines = [text for _, text in lines]
    except ExistingEnhanced:
        raise
    except PermanentSkip:
        source, lines = "whisper", []
        canonical_lines = plain_lyric_lines(path, tags)
    content, source, aligned, fallback = generate_content(
        path, tags, duration, source, lines, canonical_lines
    )
    output = install(path, content)
    markers = embed_enhanced(path, content)
    line_count = sum(1 for line in content.splitlines() if LRC_LINE.match(line.strip()))
    finish(path, "complete", f"Installed {line_count} lines; aligned={aligned}; fallback={fallback}; embedded={markers}", source=source, output=output)
    LOG.info("Enhanced LRC installed for %s (%s, %d aligned words)", path, source, aligned)


def worker_loop(do_reconcile: bool = True) -> None:
    initialise()
    if do_reconcile:
        reconcile()
    worker = f"cpu:{os.uname().nodename}:{os.getpid()}:{uuid.uuid4().hex[:8]}"
    while not _STOP.is_set():
        row = claim_job(worker)
        if row is None:
            _STOP.wait(POLL_SECONDS)
            continue
        path = Path(row["path"])
        try:
            with lease_heartbeat(path, worker):
                if not path.is_file():
                    raise PermanentSkip("Audio file no longer exists")
                process(path)
        except PermanentSkip as exc:
            finish(path, "skipped", str(exc))
            LOG.info("Skipping %s: %s", path, exc)
        except NeedsReview as exc:
            finish(path, "needs_review", str(exc))
            LOG.warning("Review required for %s: %s", path, exc)
        except TransientSourceError as exc:
            with db() as connection:
                connection.execute(
                    "UPDATE jobs SET status='queued', attempts=MAX(0,attempts-1), leased_by=NULL, leased_until=NULL, "
                    "message=?, updated_at=? WHERE path=?",
                    (str(exc)[-1000:], int(time.time()), str(path)),
                )
            LOG.warning("Transient lyric source failure for %s: %s", path, exc)
            _STOP.wait(30)
        except Exception as exc:
            attempts = int(row["attempts"]) + 1
            status = "failed" if attempts >= MAX_ATTEMPTS else "queued"
            finish(path, status, f"{type(exc).__name__}: {exc}")
            LOG.exception("Enhanced LRC processing failed for %s", path)
            _STOP.wait(min(60, 5 * attempts))


def start_worker() -> threading.Thread:
    initialise()
    # Reconcile synchronously before the HTTP API begins accepting GPU leases.
    reconcile()
    thread = threading.Thread(target=lambda: worker_loop(False), name="enhanced-lrc-worker", daemon=True)
    thread.start()
    threading.Thread(target=refresh_loop, name="enhanced-lrc-refresh", daemon=True).start()
    return thread


def webhook_authorized(headers: Any) -> bool:
    try:
        expected = WEBHOOK_SECRET_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return False
    import hmac
    supplied = str(headers.get("X-Enhanced-LRC-Token") or "").strip()
    return bool(expected) and hmac.compare_digest(supplied, expected)


def webhook_paths(payload: Any) -> list[str]:
    audio: list[str] = []
    directories: list[str] = []

    def visit(value: Any, key: str = "") -> None:
        if isinstance(value, dict):
            for child_key, child in value.items():
                visit(child, str(child_key).casefold())
        elif isinstance(value, list):
            for child in value:
                visit(child, key)
        elif isinstance(value, str) and key in {"path", "newpath", "paths"}:
            normal = value.replace("\\", "/")
            if normal.startswith(str(MEDIA_ROOT)):
                if Path(normal).suffix.casefold() in SUPPORTED_AUDIO:
                    audio.append(normal)
                elif key == "paths":
                    directories.append(normal)

    visit(payload)
    return list(dict.fromkeys(audio or directories))


def status() -> dict[str, Any]:
    initialise()
    with db() as connection:
        counts = {row["status"]: row["count"] for row in connection.execute("SELECT status, COUNT(*) count FROM jobs GROUP BY status")}
        recent = [dict(row) for row in connection.execute(
            "SELECT path,status,source,attempts,message,updated_at FROM jobs "
            "WHERE status IN ('failed','needs_review') ORDER BY updated_at DESC LIMIT 10"
        )]
    return {
        "counts": counts,
        "recentProblems": recent,
        "models": {"alignment": "WAV2VEC2_ASR_BASE_960H", "fallback": f"faster-whisper:{WHISPER_MODEL}"},
        "writeRoot": str(LYRICS_ROOT),
        "jellyfinRefreshPending": (CACHE_ROOT / "refresh-needed").is_file(),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    enqueue_parser = sub.add_parser("enqueue")
    enqueue_parser.add_argument("paths", nargs="+", type=Path)
    enqueue_parser.add_argument("--limit", type=int)
    enqueue_parser.add_argument("--reason", default="manual")
    sub.add_parser("status")
    sub.add_parser("refresh")
    sub.add_parser("reconcile")
    backfill_parser = sub.add_parser("backfill")
    backfill_parser.add_argument("--apply", action="store_true")
    backfill_parser.add_argument("--limit", type=int)
    sub.add_parser("worker")
    args = parser.parse_args()
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    initialise()
    if args.command == "enqueue":
        print(json.dumps(enqueue(args.paths, reason=args.reason, limit=args.limit), indent=2))
    elif args.command == "status":
        print(json.dumps(status(), indent=2))
    elif args.command == "refresh":
        print(json.dumps({"requested": refresh_jellyfin()}, indent=2))
    elif args.command == "reconcile":
        print(json.dumps(reconcile(), indent=2))
    elif args.command == "backfill":
        print(json.dumps(backfill(apply=args.apply, limit=args.limit), indent=2))
    else:
        worker_loop()


if __name__ == "__main__":
    main()
