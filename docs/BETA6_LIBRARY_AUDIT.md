# Beta 6.1 Library cleanup

Settings → Library cleanup:

- **Quick audit** lists probable duplicates based on cached metadata, missing names, and suspicious entries. It never opens or modifies music files.
- **Deep duplicate scan** checks normalized filenames, title/artist and durations for review candidates. Files of the same reported byte size are read in full and SHA-256 hashed. Matching hashes identify byte-for-byte identical copies.
- In each **Verified group**, use the checkbox next to each file you want removed. **Nothing is pre-selected**. Moka disallows selecting the last remaining copy from a group. Up to 100 selected tracks can be deleted in one request.
- Choose **Review & delete N selected** to see filenames and locations again. Then Android 11+ displays its own system confirmation for the selected media files. Cancel to keep all files. Refresh the library after Android approves.
- **Possible matches** that differ in file contents, such as FLAC versus MP3 encodes of the same song, remain review-only. The current implementation cannot prove they are identical recordings and does not offer deletion for them.
- Deletion is disabled on Android 10 or older. No scheduled, automatic, or silent deletion takes place.

Important: SHA-256 detects *identical files*, not audio-equivalent recordings with differing encodings or tags. Android may permanently delete selected media. Back up irreplaceable recordings before using cleanup.
