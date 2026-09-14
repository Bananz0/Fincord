#!/bin/bash
#
# Builds and runs the analyser self-test on the host. Not part of the Android build - nothing in
# Gradle references this directory.
#
# The analyser is plain C with no Android dependencies, so it compiles and runs natively, which is
# the only practical way to iterate on the DSP: a change here is verified in about two seconds
# instead of a build, install and listen cycle on a phone. On Windows, run it under WSL:
#
#   wsl.exe -d Ubuntu-24.04 bash /mnt/e/Accord/automix/src/hostTest/run.sh
#
# Exits non-zero with a count if any case fails.
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
CPP="$HERE/../main/cpp"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# The same exclusions the CMake build makes: the FFT backends aubio only uses when built against
# FFTW, Intel IPP or Accelerate, none of which are vendored.
SOURCES=$(find "$CPP/aubio" -name '*.c' | grep -v -E 'dct_fftw|dct_ipp|dct_accelerate')

gcc -O2 -Wall -DHAVE_CONFIG_H=1 -I"$CPP" -I"$CPP/aubio" \
    -o "$OUT/selftest" "$HERE/selftest.c" "$CPP/automix_analyze.c" $SOURCES -lm
echo "built ok"

fails=0
run() { # bpm tonic major label [lead-in seconds]
  echo "--- $4 ---"
  "$OUT/selftest" "$1" "$2" "$3" "${5:-0}" || fails=$((fails + 1))
}

# Tempo range covers a slow ballad through drum and bass, because the octave check and the grid
# tightening only misbehave at the ends. Keys cover major and minor and a tonic that is not C, so a
# rotation error in the Camelot mapping cannot pass.
run 120 0 1 "120bpm C major (8B)"
run 128 0 1 "128bpm C major (8B)"
run  90 0 1 "90bpm C major (8B)"
run 124 9 0 "124bpm A minor (8A)"
run 124 7 1 "124bpm G major (9B)"
run 100 1 0 "100bpm C# minor (12A)"
run 174 5 1 "174bpm F major (7B)"

# Same cases with a lead-in, so the grid backfill is exercised. A real track never starts its first
# beat at sample zero, and the backfill is what puts a grid where the incoming deck has to align.
run 120 0 1 "120bpm C major, 1.7s lead-in" 1.7
run 128 0 1 "128bpm C major, 0.9s lead-in" 0.9
run 124 9 0 "124bpm A minor, 3.2s lead-in" 3.2
run  90 0 1 "90bpm C major, 2.4s lead-in" 2.4

echo "======================"
echo "failing cases: $fails"
exit $fails
