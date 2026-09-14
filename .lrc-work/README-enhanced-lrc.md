# Accord Enhanced LRC worker

> **The desktop app lives in its own repository:**
> [Bananz0/fincord-lyrics-studio](https://github.com/Bananz0/fincord-lyrics-studio).
>
> It is the same job with a window on it, and it runs the work across whatever
> accelerators a machine has - CUDA, Intel NPU, Apple Silicon, CPU - chosen per stage.
> Deliberately a separate repo rather than a submodule: it moves at its own pace and
> nothing in the Gradle build needs it present.
>
> **The alignment rules are now one module.** `elrc_rules.py` here is a verbatim copy of
> the canonical file in that repository, imported by `enhanced_lrc.py` and copied into the
> image by the Dockerfile. Do not edit the copy - change the canonical one and re-copy.


The karaoke service runs a persistent CPU Enhanced LRC worker. Two additional
CUDA workers run under WSL on the laptop and lease work through an encrypted,
loopback-only SSH tunnel. Lidarr sends release-import, upgrade, rename, and
retag events to the lightweight receiver on the private `proxy` Docker network.

## Safety properties

- Audio is read through `/data/media`, which is mounted read-only.
- Sidecars and verified lyric tags are written through `/lyrics-target`.
- Existing word-timed LRC files are never overwritten.
- Existing line-timed sidecars are copied to `/cache/enhanced-lrc/backups` before
  replacement.
- Queue state is `/cache/enhanced-lrc/queue.sqlite3`; this is not a Jellyfin DB.
- Low-confidence, repetitive, structurally invalid, or non-monotonic output is
  marked `needs_review` and is not installed.
- Installed sidecars set a refresh marker. Jellyfin refreshes at most once per
  ten-minute batch, and a failed refresh remains pending for retry.

## Source order

1. Existing synchronized sidecar lyrics.
2. Embedded synchronized lyrics.
3. Exact artist/title/album/duration match from LRCLIB.
4. Multilingual faster-whisper fallback.

For plain canonical lyrics, Whisper supplies timing anchors while the installed
words come from the canonical lyric source. With no canonical source, stricter
Whisper confidence thresholds apply.

## Commands

```sh
docker exec karaoke python /app/enhanced_lrc.py status
docker exec karaoke python /app/enhanced_lrc.py enqueue --reason manual /data/media/music/Artist/Album
docker exec karaoke python /app/enhanced_lrc.py reconcile
docker exec karaoke python /app/enhanced_lrc.py backfill --apply
docker logs -f karaoke
```

The filesystem is authoritative: a job is complete only when the sibling LRC
and the audio tag both contain at least five word markers. Sidecars are replaced
atomically, audio mtimes are restored after embedding, and active workers renew
expiring leases. The alignment and Whisper models are cached under
`/opt/docker/appdata/karaoke/models` on the host.

Compressed audio is decoded to mono 16 kHz float samples by the FFmpeg CLI.
PyTorch/torchaudio is used only for CTC alignment, avoiding dependence on
torchaudio's optional FFmpeg ABI backend.

## Laptop accelerator workers

WSL systemd keeps the SSH tunnel, two 5070 `small` CUDA slots, and one four-thread
CPU `int8` slot running and restarts them on failure. The display-loaded 5070 Ti
is disabled when its free VRAM is insufficient. A one-slot Windows OpenVINO NPU
adapter is optional and is enabled only after a real word-timestamp test passes.
Phastos validates every returned LRC and is the only machine that installs
sidecars, embeds tags, or requests Jellyfin refreshes.

```sh
systemctl status accord-lrc-tunnel.service accord-lrc-gpu@0.service accord-lrc-gpu@2.service accord-lrc-gpu@3.service
journalctl -f -u accord-lrc-gpu@0.service -u accord-lrc-gpu@2.service -u accord-lrc-gpu@3.service
```

## Lidarr ownership

The Lidarr metadata consumer `Lyrics Enhancer` is disabled, including sidecar
creation, audio embedding, overwrite, and scheduled updates. `Import Extra
Files` remains disabled. Therefore Lidarr cannot overwrite or ingest the
worker's generated `.lrc` sidecars. Rename and retag notifications cause the
worker to inspect the new audio path and generate the correctly named sidecar.
