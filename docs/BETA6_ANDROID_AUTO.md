# Android Auto beta — driver-safe local library
Moka now provides a Media3 MediaLibraryService and MediaLibrarySession.
The browse tree includes All songs, Albums, Artists and Favorites. Local
tracks are playable through the same underlying hybrid audio player used by
the phone. Browsing uses the existing cached MediaStore index on a worker
thread, with a short refresh TTL; no full scan runs on the car UI thread.

DSP controls, destructive library operations and network login are not exposed
on the car dashboard. Compatibility must be tested with the Android Auto
Desktop Head Unit (DHU), a real wired/wireless Android Auto connection,
USB routing, phone controls and normal playback.

This is a development implementation and has not been certified/approved for
Play Store automotive distribution. A car launcher showing the app cannot
by itself confirm correct playback or full Android Auto approval.
