#!/bin/bash
set -euo pipefail
STAMP="20260822-$(date +%H%M%S)"
BACKUP="/home/glenm/backups/accord-lrc-live-$STAMP"
mkdir -p "$BACKUP"
docker stop enhanced-lrc-webhook karaoke >/dev/null
docker run --rm \
  -v /srv/storage/docker/karaoke/cache:/cache:ro \
  -v "$BACKUP":/backup \
  alpine:3.20 sh -c 'cp -a /cache/enhanced-lrc /backup/'
find /srv/storage/data/media/music -type f \
  \( -iname '*.mp3' -o -iname '*.flac' -o -iname '*.m4a' -o -iname '*.ogg' -o -iname '*.opus' \) \
  -printf '%p|%T@\n' | sort | sha256sum | cut -d' ' -f1 > "$BACKUP/mtime-before.sha256"
echo "BACKUP=$BACKUP"
docker run --rm \
  -e MEDIA_ROOT=/data/media -e LRC_WRITE_ROOT=/lyrics-target -e CACHE_ROOT=/cache \
  -v /srv/storage/data/media:/data/media:ro \
  -v /srv/storage/data/media:/lyrics-target:rw \
  -v /srv/storage/docker/karaoke/cache:/cache \
  accord-karaoke:embed-test python /app/enhanced_lrc.py backfill
