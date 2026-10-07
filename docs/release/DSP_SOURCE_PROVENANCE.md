# DSP source provenance review

Status: **OPEN — must be completed before Moka 4.0 stable/store distribution**

Moka implements DSP features that overlap conceptually with JamesDSP/DSPManager and ViPER-DDC ecosystems. Conceptual or file-format compatibility is not, by itself, evidence that source code was copied. Conversely, similar behavior is not enough to prove independent implementation. This worksheet exists so the maintainer can document the actual origin of each implementation.

## Current repository observations

A repository-wide search performed during the Beta 2 release-hardening pass did not find explicit production-source strings such as `JamesDSP`, `DSPManager`, `james34602`, `JDSP`, or copied copyright/license headers in Moka's DSP source files.

That observation is **not proof of independent implementation**. History outside the current repository, generated code, prior prototypes, AI-assisted rewrites, copied mathematical code, or earlier source material still need human review.

## Components requiring provenance sign-off

| Moka component | Current location | Function | Maintainer provenance decision |
| --- | --- | --- | --- |
| FIR EQ / filter design | `audio/dsp/DspCore.kt`, native DSP | multimodal EQ | **Pending** — original / independently reimplemented / adapted |
| IIR EQ 4/6/8/10/12 | Kotlin + `moka_dsp.cpp` | cascaded IIR EQ | **Pending** |
| VDC parser and coefficient processing | `DspCore.kt`, `DspFileLoader.kt` | ViPER-DDC-compatible profiles | **Pending**; distinguish file-format compatibility from copied implementation |
| Partitioned convolution / FFT | `moka_dsp.cpp` | IRS convolution | **Pending** |
| IRS resampling | Kotlin/native DSP | resampling | **Pending** |
| Look-ahead limiter | Kotlin/native DSP | output protection | **Pending** |
| Headroom estimation | `DspRuntime.kt` | setup-time safety gain | **Pending** |
| PCM conversion / DSP runtime plumbing | DSP/audio packages | transport/runtime | **Pending** |

## JamesDSP / DSPManager review

Moka's existing third-party notice states that its DSP design is influenced by the Android DSP ecosystem around JamesDSP. JamesDSP's upstream README also states that its engine frame is based on Antti S. Lankila's DSPManager.

Before Moka stable distribution, review:

1. The exact JamesDSP/DSPManager files, commits, articles, snippets, or algorithms that were consulted.
2. Whether any source expression (not just a mathematical concept) was copied, translated, mechanically ported, or closely adapted.
3. The license applying to each such upstream source at the exact revision consulted.
4. Whether attribution, source disclosure, NOTICE text, reciprocal licensing, or other obligations apply.
5. Whether any uncertain implementation should be replaced with a clean independent implementation before release.

Do **not** mark this review complete merely because Moka uses different class/function names.

## Suggested evidence to record

For each DSP subsystem, add a short statement such as:

- **Original implementation:** designed/written specifically for Moka; references were mathematical/public specifications only.
- **Independent reimplementation:** behavior/file format studied, but source expression was not copied; list specifications/references used.
- **Adapted:** identify upstream project, file, commit/revision, license, modifications, and required notices.
- **Unknown:** treat as a release blocker until resolved.

## Release sign-off

The release owner should not check the final provenance gate in `BETA_CHECKLIST.md` until every row above has an evidence-backed disposition and any required third-party obligations have been added to the distributed notices/source package.
