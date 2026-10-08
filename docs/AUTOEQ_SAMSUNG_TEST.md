# Unreleased AutoEq and Samsung loudness test

AutoEq source is public GitHub results/INDEX.md and its parametric output files. Moka fetches them after user input; it does not embed AutoEq measurement data or copy GPLv3 DDCToolbox code. Raw measurement rights vary by contributor and need review before commercial distribution.

To compare Samsung decode throughput on the same FLAC files:

```bash
adb -s RFCY21RZR3P logcat -c
adb -s RFCY21RZR3P logcat -s MokaLoudness:I '*:S'
```

Compare `analysisMs`, `speed` and `decoder throughput ... idleWaitMs`; also compare integrated LUFS before/after. No speedup is guaranteed until measured.
