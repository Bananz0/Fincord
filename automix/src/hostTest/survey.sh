#!/bin/bash
#
# Measures the Automix analyser over a real music library and prints one row per window.
#
# Why this exists. `MIN_TEMPO_CONFIDENCE` decides whether two tracks may be mixed, and it was set
# from about twenty numbers - every one of them measured over the first two minutes of a track. The
# transition now also measures a *tail* window, and on the first device capture the tail confidences
# came in at 0.110 and 0.116 against a head window of 0.269 on the same track, with every single
# refusal landing on that gate. Either the threshold is wrong for tails or those tracks were
# unlucky, and the difference between those two answers is a distribution.
#
# On a phone that means playing hundreds of tracks. On a machine that holds the files it is a loop.
#
# Usage, from a checkout of this repository on a host with gcc and ffmpeg:
#
#   automix/src/hostTest/survey.sh <music-dir> [track-count] > survey.tsv
#
# Output is tab-separated with a header, one row per window per track, so the head and tail of the
# same track can be compared directly and the whole thing loaded anywhere.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
CPP="$HERE/../main/cpp"
MUSIC=${1:?usage: survey.sh <music-dir> [track-count]}
LIMIT=${2:-200}

# The same windows the app uses, from WindowAnalyzer. Changing them here without changing them
# there makes this survey measure something the transition never asks about.
HEAD_SECONDS=45
TAIL_SECONDS=90
# What TrackAnalyzer stores, kept so the survey can show all three and the threshold can be
# compared against the population it was actually calibrated on.
STORED_SECONDS=120

RATE=44100

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# The same exclusions the CMake build and the self-test make: FFT backends aubio only uses when
# built against FFTW, Intel IPP or Accelerate, none of which are vendored.
SOURCES=$(find "$CPP/aubio" -name '*.c' | grep -v -E 'dct_fftw|dct_ipp|dct_accelerate')

echo "building..." >&2
gcc -O2 -w -DHAVE_CONFIG_H=1 -I"$CPP" -I"$CPP/aubio" \
    -o "$OUT/analyse" "$HERE/analyse_stdin.c" "$CPP/automix_analyze.c" $SOURCES -lm || exit 1
echo "built ok" >&2

printf 'file\twindow\tstart_s\tanalysed_s\tbpm\tconfidence\tbeats\tdb_index\tdb_conf\tkey\tkey_strength\tdoubled_from\n'

# One window of one file. Decoding is ffmpeg's job; -ss before -i seeks rather than reading through.
measure() { # file window start length
  local file=$1 window=$2 start=$3 length=$4
  local row
  row=$(ffmpeg -v quiet -ss "$start" -t "$length" -i "$file" -f f32le -ac 1 -ar "$RATE" - 2>/dev/null \
        | "$OUT/analyse" "$RATE" 2>/dev/null)
  [ -z "$row" ] && return
  printf '%s\t%s\t%s\t%s\n' "$(basename "$file")" "$window" "$start" "$row"
}

count=0
# -print0 and read -d so paths with spaces, which is most of a music library, survive the loop.
while IFS= read -r -d '' file; do
  duration=$(ffprobe -v quiet -show_entries format=duration -of csv=p=0 "$file" 2>/dev/null)
  duration=${duration%%.*}
  # A track shorter than the tail window has no distinct head and tail to compare, which is the
  # entire question being asked.
  case "$duration" in ''|*[!0-9]*) continue;; esac
  [ "$duration" -lt $((TAIL_SECONDS + 30)) ] && continue

  measure "$file" head 0 "$HEAD_SECONDS"
  measure "$file" stored 0 "$STORED_SECONDS"
  measure "$file" tail "$((duration - TAIL_SECONDS))" "$TAIL_SECONDS"

  count=$((count + 1))
  [ $((count % 10)) -eq 0 ] && echo "  $count tracks" >&2
  [ "$count" -ge "$LIMIT" ] && break
# Shuffled, not walked in order. A library is laid out by artist, so the first hundred files are
# two or three artists and a survey of them measures those artists rather than the library - which
# is exactly the mistake that produced a confidence threshold from twenty tracks. Sampled at four
# times the limit so the duration filter above has something to discard.
done < <(find "$MUSIC" -type f \( -iname '*.flac' -o -iname '*.mp3' -o -iname '*.m4a' \
         -o -iname '*.ogg' -o -iname '*.opus' -o -iname '*.wav' \) -print0 2>/dev/null \
         | shuf -z -n $((LIMIT * 4)))

echo "done: $count tracks" >&2
