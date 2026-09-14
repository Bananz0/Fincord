/*
 * aubio build configuration for Android.
 *
 * Upstream generates this with waf by probing the host. Nothing probes here, and nothing needs to:
 * the NDK's libc and libm are the same on every ABI Fincord ships, and every optional acceleration
 * backend aubio knows about (FFTW, Intel IPP, Accelerate, ATLAS/OpenBLAS, libsamplerate, libav,
 * libsndfile) is absent on Android. Leaving them all undefined selects aubio's own Ooura FFT and
 * its plain C vector maths, which is what we want anyway - a self-contained library with no
 * transitive licence or packaging story.
 */

#ifndef AUBIO_ANDROID_CONFIG_H
#define AUBIO_ANDROID_CONFIG_H

#define HAVE_STDLIB_H 1
#define HAVE_STDIO_H 1
#define HAVE_MATH_H 1
#define HAVE_STRING_H 1
#define HAVE_ERRNO_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDARG_H 1

/* clang in the NDK is C99 and beyond, so aubio's logging macros can take __VA_ARGS__. */
#define HAVE_C99_VARARGS_MACROS 1

/*
 * Copy and zero whole vectors with memcpy/memset instead of element loops. This also switches off
 * aubio's HAVE_NOOPT fallback path, which exists only for builds with no faster option at all.
 */
#define HAVE_MEMCPY_HACKS 1

#endif /* AUBIO_ANDROID_CONFIG_H */
