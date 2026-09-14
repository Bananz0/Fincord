/*
 * The public aubio API that is actually compiled into this module.
 *
 * Replaces upstream's `aubio.h`, which pulls in the headers of the io, synth and notes modules that
 * are not vendored - see `aubio/README.md`. Including only what is built means a call to something
 * that is missing fails at compile time, where it is obvious, rather than at link time.
 */

#ifndef AUTOMIX_AUBIO_SUBSET_H
#define AUTOMIX_AUBIO_SUBSET_H

#ifdef __cplusplus
extern "C" {
#endif

#include "aubio/types.h"
#include "aubio/fvec.h"
#include "aubio/cvec.h"
#include "aubio/lvec.h"
#include "aubio/fmat.h"
#include "aubio/musicutils.h"
#include "aubio/vecutils.h"
#include "aubio/temporal/filter.h"
#include "aubio/temporal/a_weighting.h"
#include "aubio/temporal/c_weighting.h"
#include "aubio/spectral/fft.h"
#include "aubio/spectral/phasevoc.h"
#include "aubio/spectral/specdesc.h"
#include "aubio/spectral/filterbank.h"
#include "aubio/spectral/filterbank_mel.h"
#include "aubio/spectral/awhitening.h"
#include "aubio/onset/onset.h"
#include "aubio/tempo/tempo.h"

#ifdef __cplusplus
}
#endif

#endif /* AUTOMIX_AUBIO_SUBSET_H */
