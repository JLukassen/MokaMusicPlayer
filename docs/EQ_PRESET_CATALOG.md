# Beta 6 — Multimodal equalizer preset catalog

The Multimodal Equalizer now has one **Presets…** selector containing all
built-in curves followed by your **My Saved Presets**.

The built-in styles are: Acoustic, Bass, Beats, Classic, Clear, Deep Bass,
Dubstep, Electronic, Flat, Hardstyle, Hip-Hop, Jazz, Metal, Movie, Pop, R&B,
Rock, Vocal Booster, Warm and Moka Reference.

Each has exactly fifteen gains corresponding to Moka's bands from 25 Hz to
16 kHz. These are Moka's original approximations inspired by common sound
profiles; they are not copies of another app's equalizer curves.

Selecting one enables EQ and the DSP master switch, preserves the current
FIR/IIR mode and interpolator, and does not modify DDC, convolver, limiter,
normalization, or your saved custom presets. The UI shows Custom when the
current band values don't match a built-in curve.

Use **EQ preset name → Save EQ** to store a custom curve. It will appear under
My Saved Presets. You can edit it, save over the name, or delete it from the
same section.

The preset dropdown dismisses when a choice is selected or tapped outside,
and it scrolls vertically when there are more entries than fit on screen.
This does not update audio files on disk.

Samsung loudness decoding has not changed in this EQ-only update. Benchmark
those throughput changes separately before publishing Beta 6.
