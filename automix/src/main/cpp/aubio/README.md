# Vendored aubio

aubio 0.4.9 (https://aubio.org), GPL-3.0-or-later, (C) 2003-2019 Paul Brossier and contributors.
See `COPYING` and `AUTHORS`, which are copied here unmodified from the release tarball. Fincord is
GPL-3.0 itself, so linking this is a plain licence match rather than an exception.

Only a subset of `src/` is here. What is missing and why:

- `io/` needs libsndfile, libav or CoreAudio to build, and Fincord has no use for any of them: the
  samples arrive from `MediaExtractorCompat`/`MediaCodec`, already decoded, out of the media3 cache.
- `synth/` and `notes/` are a sampler, a wavetable and a note segmenter. Nothing here plays or
  synthesises audio.
- `spectral/dct_fftw.c`, `dct_ipp.c`, `dct_accelerate.c` are backends for libraries that do not
  exist on Android. `dct_ooura.c` and `dct_plain.c` are the ones that build, and `dct.c` picks
  between them at runtime.
- `utils/windll.c` is a Windows DLL entry point.

`src/aubio.h`, the umbrella header, is deliberately **not** vendored: it `#include`s the headers of
every one of those excluded modules, so keeping it would have meant vendoring headers declaring an
API that is not compiled in - a trap for anyone who later calls one and gets a link error instead
of a compile error. `../aubio_subset.h` is the umbrella for what is actually built.

`config.h` beside it is written by hand. Upstream generates one with waf; this build has no waf, and
the answers for Android are fixed and short enough to state outright.

To update: replace the directories listed above from a new release tarball, keep this file,
`config.h` and `../aubio_subset.h` in step, and re-check the exclusion list against the new `src/`.
