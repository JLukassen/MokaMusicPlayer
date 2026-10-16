# Beta 6.1 Library cleanup

Settings → Library cleanup:

- **Quick audit** lists probable duplicates based on cached metadata, missing names, and suspicious entries. It never opens or modifies music files.
- **Deep duplicate scan** checks normalized filenames, title/artist and durations for review candidates. Files of the same reported byte size are read in full and SHA-256 hashed. Matching hashes identify byte-for-byte identical copies.
- In each **Verified group**, use the checkbox next to each file you want removed. **Nothing is pre-selected**. Moka disallows selecting the last remaining copy from a group. Up to 100 selected tracks can be deleted in one request.
- Choose **Review & delete N selected** to see filenames and locations again. Then Android 11+ displays its own system confirmation for the selected media files. Cancel to keep all files. Refresh the library after Android approves.
- **Possible matches** (e.g., FLAC versus MP3 with similar names and durations) can now also be manually selected for deletion. These matches are **not** verified by audio fingerprints; files may be different takes, masters, or encodings. A conspicuous warning and an additional acknowledgment checkbox are required before Android's own deletion consent.
- The keep-one rule applies across both verified groups and overlapping suspected groups, so even mixed selections must preserve one track per group.
- No files are selected by default. If a real duplicate is missing from suspected groups, do not assume this scanner recognizes it; fingerprinting remains future work.
- Deletion is disabled on Android 10 or older. No scheduled, automatic, or silent deletion takes place.

Important: SHA-256 detects *identical files*, not audio-equivalent recordings with differing encodings or tags. Android may permanently delete selected media. Back up irreplaceable recordings before using cleanup.
