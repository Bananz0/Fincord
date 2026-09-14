#!/usr/bin/env python3
"""Expiring GPU job leases for remote Enhanced LRC workers."""

from __future__ import annotations

import json
import logging
import secrets
import time
from pathlib import Path
from typing import Any

import enhanced_lrc as lrc


LOG = logging.getLogger("accord-enhanced-lrc-gpu-api")
LEASE_SECONDS = 60 * 60


def initialise() -> None:
    lrc.initialise()
    with lrc.db() as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS gpu_leases (
                lease_id TEXT PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                fingerprint TEXT NOT NULL,
                worker TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                metadata TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        # A restarted worker may have lost the response that identified its old
        # lease. Enforce one slot per worker in SQLite itself, including while an
        # older service image is still running.
        connection.execute(
            """
            CREATE TRIGGER IF NOT EXISTS gpu_one_lease_per_worker
            BEFORE INSERT ON gpu_leases
            BEGIN
                UPDATE jobs SET status='queued', attempts=MAX(0,attempts-1),
                    leased_by=NULL, leased_until=NULL,
                    message='Superseded worker lease recovered', updated_at=CAST(strftime('%s','now') AS INTEGER)
                    WHERE path IN (SELECT path FROM gpu_leases WHERE worker=NEW.worker)
                      AND status='leased';
                DELETE FROM gpu_leases WHERE worker=NEW.worker;
            END
            """
        )
        connection.execute(
            "DELETE FROM gpu_leases WHERE NOT EXISTS ("
            "SELECT 1 FROM jobs WHERE jobs.path=gpu_leases.path AND jobs.status='leased' "
            "AND jobs.leased_by=gpu_leases.worker)"
        )
        recover_expired(connection)


def recover_expired(connection: Any) -> None:
    now = int(time.time())
    expired = connection.execute(
        "SELECT path FROM gpu_leases WHERE expires_at < ?", (now,)
    ).fetchall()
    for row in expired:
        connection.execute(
            "UPDATE jobs SET status='queued', attempts=MAX(0,attempts-1), leased_by=NULL, leased_until=NULL, "
            "message='GPU lease expired and was recovered', updated_at=? "
            "WHERE path=? AND status='leased'",
            (now, row["path"]),
        )
    connection.execute("DELETE FROM gpu_leases WHERE expires_at < ?", (now,))


def _release(lease_id: str, path: Path, status: str, message: str, *, required_lane: str | None = None,
             refund_attempt: bool = False) -> None:
    with lrc.db() as connection:
        connection.execute("DELETE FROM gpu_leases WHERE lease_id=?", (lease_id,))
        connection.execute(
            "UPDATE jobs SET status=?, message=?, required_lane=COALESCE(?,required_lane), "
            "attempts=MAX(0,attempts-?), leased_by=NULL, leased_until=NULL, updated_at=? "
            "WHERE path=? AND status='leased'",
            (status, message[-1000:], required_lane, int(refund_attempt), int(time.time()), str(path)),
        )


def lease(worker: str, capability: str = "cuda") -> dict[str, Any] | None:
    worker = worker.strip()[:100]
    if not worker:
        raise ValueError("A worker name is required")
    capability = capability.strip().casefold()[:20]
    if capability not in {"cuda", "cpu", "npu"}:
        raise ValueError("Worker capability must be cuda, cpu, or npu")
    for _ in range(25):
        now = int(time.time())
        lease_id = secrets.token_urlsafe(24)
        with lrc.db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            recover_expired(connection)
            row = connection.execute(
                "SELECT * FROM jobs WHERE status='queued' AND attempts < ? "
                "AND (required_lane IS NULL OR required_lane=?) ORDER BY updated_at,path LIMIT 1",
                (lrc.MAX_ATTEMPTS, capability),
            ).fetchone()
            if row is None:
                connection.commit()
                return None
            connection.execute(
                "UPDATE jobs SET status='leased', attempts=attempts+1, leased_by=?, leased_until=?, "
                "message=?, updated_at=? WHERE path=? AND status='queued'",
                (worker, now + 300, f"Leased to {worker}", now, row["path"]),
            )
            connection.execute(
                "INSERT INTO gpu_leases(lease_id,path,fingerprint,worker,expires_at,metadata,created_at) "
                "VALUES(?,?,?,?,?,?,?)",
                (lease_id, row["path"], row["fingerprint"], worker, now + 300, "{}", now),
            )
            connection.commit()

        path = Path(row["path"])
        try:
            if not path.is_file():
                raise lrc.PermanentSkip("Audio file no longer exists")
            current_fingerprint = lrc.fingerprint(path)
            if current_fingerprint != row["fingerprint"]:
                raise RuntimeError("Audio changed after it was queued")
            existing = lrc.sidecar_path(path)
            if existing.is_file():
                content = existing.read_text(encoding="utf-8-sig", errors="replace")
                if lrc.is_enhanced(content):
                    markers = lrc.embed_enhanced(path, content)
                    lrc.finish(path, "complete", f"GPU lease backfilled existing sidecar ({markers} markers)",
                               source="sidecar-backfill", output=existing)
                    with lrc.db() as connection:
                        connection.execute("DELETE FROM gpu_leases WHERE lease_id=?", (lease_id,))
                    continue
            tags, duration = lrc.probe(path)
            try:
                source, lines = lrc.lyric_source(path, tags, duration)
                canonical_lines = [text for _, text in lines]
            except lrc.ExistingEnhanced:
                _release(lease_id, path, "skipped", "An Enhanced LRC already exists")
                continue
            except lrc.PermanentSkip:
                source, lines = "whisper", []
                canonical_lines = lrc.plain_lyric_lines(path, tags)
            safe_tags = {
                key: value for key, value in tags.items()
                if key not in {"lyrics", "syncedlyrics", "unsyncedlyrics", "_lrclib_plain"}
            }
            metadata = {
                "path": str(path.relative_to(lrc.MEDIA_ROOT)),
                "name": path.name,
                "fingerprint": current_fingerprint,
                "duration": duration,
                "tags": safe_tags,
                "source": source,
                "lines": lines,
                "canonicalLines": canonical_lines,
            }
            with lrc.db() as connection:
                connection.execute(
                    "UPDATE gpu_leases SET metadata=?, expires_at=? WHERE lease_id=?",
                    (json.dumps(metadata, ensure_ascii=False), int(time.time()) + LEASE_SECONDS, lease_id),
                )
                connection.execute(
                    "UPDATE jobs SET leased_until=? WHERE path=? AND leased_by=? AND status='leased'",
                    (int(time.time()) + LEASE_SECONDS, str(path), worker),
                )
            metadata["leaseId"] = lease_id
            metadata["audioPath"] = f"/v1/enhanced-lrc/gpu/jobs/{lease_id}/audio"
            return metadata
        except lrc.PermanentSkip as exc:
            _release(lease_id, path, "skipped", str(exc))
        except lrc.TransientSourceError as exc:
            with lrc.db() as connection:
                connection.execute("DELETE FROM gpu_leases WHERE lease_id=?", (lease_id,))
                connection.execute(
                    "UPDATE jobs SET status='queued',attempts=MAX(0,attempts-1),leased_by=NULL,leased_until=NULL,"
                    "message=?,updated_at=? WHERE path=?",
                    (str(exc)[-1000:], int(time.time()), str(path)),
                )
            LOG.warning("Transient source failure while preparing %s: %s", path, exc)
            return None
        except Exception as exc:
            LOG.exception("Could not prepare GPU lease for %s", path)
            _release(lease_id, path, "queued", f"GPU preparation failed: {type(exc).__name__}: {exc}")
            raise
    return None


def get_lease(lease_id: str) -> tuple[Any, dict[str, Any]]:
    with lrc.db() as connection:
        row = connection.execute(
            "SELECT * FROM gpu_leases WHERE lease_id=? AND expires_at>=?",
            (lease_id, int(time.time())),
        ).fetchone()
    if row is None:
        raise KeyError("GPU lease is missing or expired")
    return row, json.loads(row["metadata"])


def audio_path(lease_id: str) -> Path:
    row, _ = get_lease(lease_id)
    path = Path(row["path"])
    if not path.is_file() or lrc.fingerprint(path) != row["fingerprint"]:
        raise FileNotFoundError("Leased audio is missing or changed")
    return path


def heartbeat(lease_id: str, worker: str) -> dict[str, Any]:
    now = int(time.time())
    with lrc.db() as connection:
        row = connection.execute(
            "SELECT path,worker FROM gpu_leases WHERE lease_id=? AND expires_at>=?",
            (lease_id, now),
        ).fetchone()
        if row is None or row["worker"] != worker:
            raise KeyError("GPU lease is missing, expired, or belongs to another worker")
        connection.execute("UPDATE gpu_leases SET expires_at=? WHERE lease_id=?", (now + LEASE_SECONDS, lease_id))
        connection.execute(
            "UPDATE jobs SET leased_until=?,updated_at=? WHERE path=? AND leased_by=? AND status='leased'",
            (now + LEASE_SECONDS, now, row["path"], worker),
        )
    return {"status": "leased", "leasedUntil": now + LEASE_SECONDS}


def _validate_uploaded(content: str, duration: float) -> tuple[int, int]:
    if len(content.encode("utf-8")) > 1024 * 1024:
        raise ValueError("Enhanced LRC exceeds the 1 MiB result limit")
    if "[by:Accord Enhanced LRC (" not in content:
        raise ValueError("Result does not carry the Accord generator marker")
    rendered = [line.strip() for line in content.splitlines() if lrc.LRC_LINE.match(line.strip())]
    if not rendered:
        raise ValueError("Result contains no timed lyric lines")
    aligned = 0
    for line in rendered:
        cues = lrc.WORD_CUE.findall(line)
        if len(cues) < 2:
            raise ValueError("Every lyric line must have word and end cues")
        aligned += len(cues) - 1
    lrc.validate(rendered, duration, aligned, 0)
    return len(rendered), aligned


def complete(lease_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    row, metadata = get_lease(lease_id)
    path = Path(row["path"])
    if not path.is_file() or lrc.fingerprint(path) != row["fingerprint"]:
        _release(lease_id, path, "queued", "Audio changed during GPU processing")
        raise RuntimeError("Audio changed during GPU processing")
    content = str(payload.get("content") or "")
    lines, words = _validate_uploaded(content, float(metadata["duration"]))
    try:
        output = lrc.install(path, content)
        markers = lrc.embed_enhanced(path, content)
        source = str(payload.get("source") or "gpu")[:200]
        lrc.finish(
            path, "complete", f"GPU installed {lines} lines; aligned={words}; embedded={markers}",
            source=source, output=output,
        )
    except lrc.ExistingEnhanced as exc:
        output = lrc.sidecar_path(path)
        content = output.read_text(encoding="utf-8-sig", errors="replace")
        markers = lrc.embed_enhanced(path, content)
        lrc.finish(path, "complete", f"GPU race recovered by embedding existing sidecar ({markers} markers)",
                   source="sidecar-backfill", output=output)
    finally:
        with lrc.db() as connection:
            connection.execute("DELETE FROM gpu_leases WHERE lease_id=?", (lease_id,))
    LOG.info("Accepted GPU Enhanced LRC for %s (%d words)", path, words)
    return {"status": "complete", "lines": lines, "words": words, "output": str(output)}


def fail(lease_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    row, _ = get_lease(lease_id)
    path = Path(row["path"])
    message = str(payload.get("message") or "GPU worker failed")[-1000:]
    retry = bool(payload.get("retry"))
    reroute_lane = str(payload.get("rerouteLane") or "").strip().casefold() or None
    if reroute_lane not in {None, "cuda", "cpu", "npu"}:
        raise ValueError("rerouteLane must be cuda, cpu, or npu")
    with lrc.db() as connection:
        job = connection.execute("SELECT attempts FROM jobs WHERE path=?", (str(path),)).fetchone()
    if not retry:
        status = "needs_review"
    elif job and (reroute_lane or job["attempts"] < lrc.MAX_ATTEMPTS):
        status = "queued"
    else:
        status = "failed"
    _release(lease_id, path, status, message, required_lane=reroute_lane, refund_attempt=bool(reroute_lane))
    return {"status": status}
