cmake_minimum_required(VERSION 3.22.1)

project(automix C)

#
# aubio's subset, as vendored in aubio/ - see aubio/README.md for what is missing and why. Listed
# by directory rather than by file: within these directories every .c is wanted, and a glob would
# quietly pick up whatever a future re-vendoring drops in, including the backends that do not build
# on Android.
#
set(AUBIO_SOURCES
        aubio/cvec.c
        aubio/fmat.c
        aubio/fvec.c
        aubio/lvec.c
        aubio/mathutils.c
        aubio/musicutils.c
        aubio/vecutils.c

        aubio/onset/onset.c
        aubio/onset/peakpicker.c

        aubio/pitch/pitch.c
        aubio/pitch/pitchfcomb.c
        aubio/pitch/pitchmcomb.c
        aubio/pitch/pitchschmitt.c
        aubio/pitch/pitchspecacf.c
        aubio/pitch/pitchyin.c
        aubio/pitch/pitchyinfast.c
        aubio/pitch/pitchyinfft.c

        aubio/spectral/awhitening.c
        aubio/spectral/dct.c
        aubio/spectral/dct_ooura.c
        aubio/spectral/dct_plain.c
        aubio/spectral/fft.c
        aubio/spectral/filterbank.c
        aubio/spectral/filterbank_mel.c
        aubio/spectral/mfcc.c
        aubio/spectral/ooura_fft8g.c
        aubio/spectral/phasevoc.c
        aubio/spectral/specdesc.c
        aubio/spectral/statistics.c
        aubio/spectral/tss.c

        aubio/tempo/beattracking.c
        aubio/tempo/tempo.c

        aubio/temporal/a_weighting.c
        aubio/temporal/biquad.c
        aubio/temporal/c_weighting.c
        aubio/temporal/filter.c
        aubio/temporal/resampler.c

        aubio/utils/hist.c
        aubio/utils/log.c
        aubio/utils/parameter.c
        aubio/utils/scale.c
)

add_library(automix SHARED
        automix_analyze.c
        automix_jni.c
        ${AUBIO_SOURCES}
)

target_include_directories(automix PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/aubio
)

#
# HAVE_CONFIG_H points aubio_priv.h at the hand-written aubio/config.h. Everything the library needs
# to know about the platform is decided there rather than by compiler flags, so this is the only
# definition the build itself has to supply.
#
target_compile_definitions(automix PRIVATE HAVE_CONFIG_H=1)

#
# aubio predates several warnings clang now enables by default and trips them in code that is
# correct; silencing them here keeps a real warning in Fincord's own two files visible instead of
# buried under a few hundred from a dependency.
#
#
# Deliberately no -ffast-math. It would buy nothing measurable - a four-minute track analyses in
# well under a second either way - and it licenses the compiler to assume no NaN or infinity, which
# is not a promise anyone can make about arbitrary decoded audio.
#
target_compile_options(automix PRIVATE
        -Wall
        -Wno-unused-parameter
        -Wno-sign-compare
)

target_link_libraries(automix m)
