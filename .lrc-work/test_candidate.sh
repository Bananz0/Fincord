#!/bin/bash
set -euo pipefail
docker run --rm --entrypoint python accord-karaoke:embed-test -m py_compile \
  /app/enhanced_lrc.py /app/enhanced_lrc_gpu_api.py /app/service.py
docker run --rm --entrypoint python accord-karaoke:embed-test \
  -c 'import mutagen; print("mutagen", mutagen.version_string)'

rm -rf /home/glenm/accord-lrc-test
mkdir -p /home/glenm/accord-lrc-test/media /home/glenm/accord-lrc-test/cache
SAMPLE=$(find /srv/storage/data/media/music -type f -name '*.lrc' \
  -exec grep -Il '<[0-9][0-9]*:[0-9][0-9][.:][0-9][0-9]*>' {} + 2>/dev/null | head -1 || true)
AUDIO="${SAMPLE%.lrc}.mp3"
[[ -f "$AUDIO" ]] || AUDIO="${SAMPLE%.lrc}.flac"
EXT="${AUDIO##*.}"
echo "sample=$SAMPLE"
cp "$SAMPLE" /home/glenm/accord-lrc-test/media/sample.lrc
cp "$AUDIO" "/home/glenm/accord-lrc-test/media/sample.$EXT"
stat -c 'before_mtime=%Y size=%s' "/home/glenm/accord-lrc-test/media/sample.$EXT"

docker run --rm \
  -e MEDIA_ROOT=/data/media -e LRC_WRITE_ROOT=/lyrics-target -e CACHE_ROOT=/cache \
  -v /home/glenm/accord-lrc-test/media:/data/media:rw \
  -v /home/glenm/accord-lrc-test/media:/lyrics-target:rw \
  -v /home/glenm/accord-lrc-test/cache:/cache \
  accord-karaoke:embed-test python /app/enhanced_lrc.py backfill --apply

stat -c 'after_mtime=%Y size=%s' "/home/glenm/accord-lrc-test/media/sample.$EXT"
docker run --rm -e MEDIA_ROOT=/test -e LRC_WRITE_ROOT=/test \
  -v /home/glenm/accord-lrc-test/media:/test:ro \
  --entrypoint python accord-karaoke:embed-test \
  -c 'import sys;sys.path.insert(0,"/app");import enhanced_lrc as e;from pathlib import Path;p=next(x for x in Path("/test").iterdir() if x.suffix!=".lrc");print("verified_markers",e.embedded_word_cues(p))'
