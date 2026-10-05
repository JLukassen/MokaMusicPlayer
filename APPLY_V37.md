# Apply Moka v3.7.0-alpha01 to your existing GitHub checkout

This overlay is recommended for your existing `MokaMusicPlayer` Git checkout because it preserves the repository's Gradle wrapper and `.git` history.

Commit or back up current work first. Extract this overlay, then copy its contents into the repository root.

```bash
cd ~/Desktop/MokaMusicPlayer-official
cp -a /path/to/MokaMusicPlayer-v3.7-overlay/. .
./fix-native-build.sh
./gradlew clean :app:assembleDebug --no-build-cache
```

For a signed release build, keep your ignored `keystore.properties` in the repository root and run:

```bash
./gradlew clean :app:assembleRelease
```

After committing the v3.7 changes, publish the alpha from the terminal with:

```bash
./scripts/publish-release.sh v3.7.0-alpha01
```
