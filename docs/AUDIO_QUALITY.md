# Moka audio-quality design

## Pure playback

For compatible local lossless content, Moka attempts to preserve the source sample rate and integer PCM precision through its direct engine. If the platform decoder or output cannot satisfy the requested direct format, Moka falls back rather than silently claiming a bit-perfect path.

## DSP playback

DSP converts decoded PCM to 32-bit float, applies the configured processing chain, then writes float PCM to AudioTrack. Because DSP changes the samples, Moka never calls DSP playback source bit-perfect.

## DSP order

1. automatic headroom
2. track/album normalization
3. ViPER-DDC-compatible filter cascade
4. FIR or IIR equalizer
5. IRS convolution
6. look-ahead limiter / post gain

## Native engine

The native engine handles FIR, high-order IIR, DDC, convolution, normalization and limiting. FIR EQ and IRS may be combined during setup to reduce runtime FFT work. Sparse delayed-scalar impulses bypass FFT convolution.

## Headroom

Moka estimates positive gain from enabled stages and applies conservative pre-DSP attenuation. This is preferable to allowing multiple boosts to pile into the limiter continuously.

## Resampling

Moka does not resample program audio merely to chase a DAC's maximum advertised rate. Setup-time DSP assets such as impulse responses can be converted to the active sample rate with a long Blackman-Harris windowed-sinc kernel.

## Peaks

The app exposes sample peaks plus a 4× inter-sample estimate. The latter is useful for detecting likely reconstruction overshoots but is not described as laboratory-certified true peak.


## USB route-specific safety policy (Beta 1)

Moka preserves direct / exact-bit-perfect-capable USB routing on devices that behave normally. Pixel 8a is a tested exception: its DSP-off integer bit-perfect route produced dangerously high output relative to the Android media-volume setting. On Pixel 8a while USB audio is routed, Moka therefore keeps the user's saved DSP profile active and does not request a BIT_PERFECT mixer. This keeps the preferred DSP sound and working volume behavior without flattening every other USB route. The user's stored DSP master preference is left unchanged and becomes effective again after USB is disconnected.
