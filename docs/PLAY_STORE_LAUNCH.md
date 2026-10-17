# Moka Music Player — Google Play first upload

This is a checklist for the developer's first **internal testing** release. The existing GitHub Beta 6 APK is **debug-signed and cannot be submitted as the Play upload**. Keep GitHub Beta 6 (`v4.0.0-beta06`) unchanged.

## 1. Create the app in Google Play Console

1. Open https://play.google.com/console/ and choose **Create app**.
2. App name: **Moka Music Player**; type **App**; free or paid: **Free** if the app is free. Confirm the required app declarations truthfully.
3. Set up **Test and release → Internal testing**. Don't create a Production release yet.
4. Preserve the existing app ID **`com.mokamusic.player`**. Once a bundle is uploaded, package names and signing setup become long-term commitments.
5. Choose **Yoo Shastan** as the *public* developer name if available and if it matches your Play account information. Personal developer accounts still must provide verified legal identity and any required public details to Google. A pseudonym does not guarantee anonymity.

Current Android configuration (from `app/build.gradle.kts`): target API 36, min API 26, arm64-v8a/x86_64, current Beta 6 versionCode 38. **Do not upload a lower or reused versionCode to the same Play app after its first accepted release.**

## 2. Generate a long-lived *upload* key on your own Fedora PC

On Fedora, open a terminal in the cloned Moka repository. Run these commands **locally, not in GitHub Actions, and do not send the keystore or passwords to ChatGPT or commit them to Git**:

```bash
mkdir -p "$HOME/.local/share/moka-play-signing"
chmod 700 "$HOME/.local/share/moka-play-signing"

keytool -genkeypair -v \
  -keystore "$HOME/.local/share/moka-play-signing/moka-upload.p12" \
  -storetype PKCS12 \
  -alias moka-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` prompts for the password and certificate information. Use a strong, unique password. Then run `chmod 600 "$HOME/.local/share/moka-play-signing/moka-upload.p12"`.

Create **`keystore.properties`** in the top-level Moka repository, using your actual Linux home folder and the password you entered:

```properties
storeFile=/home/YOUR_LINUX_USER/.local/share/moka-play-signing/moka-upload.p12
storePassword=YOUR_PRIVATE_KEYSTORE_PASSWORD
keyAlias=moka-upload
keyPassword=YOUR_PRIVATE_KEYSTORE_PASSWORD
```

PKCS12 typically uses the same password for the store and key. **`keystore.properties` is excluded by `.gitignore` but contains sensitive plaintext passwords.** Set permissions with `chmod 600 keystore.properties`; don't email it, commit it, share screenshots of it, or upload it to cloud CI artifacts. Use a password manager for the password. Store an encrypted **offline backup** of your upload keystore and test that you can restore it.

For a new Play app, enroll in **Play App Signing** and let Google generate/manage the app-signing key. Your private keystore above is the *upload key*, not the Google-managed distribution key. Even if a lost upload key can be reset, protect it carefully.

## 3. Produce the signed Android App Bundle

```bash
git fetch origin
git switch play-store-launch
git pull --ff-only origin play-store-launch
bash scripts/build-play-aab.sh
```

The script verifies required local signing configuration, runs Gradle unit tests and `:app:bundleRelease`, and checks the bundle's JAR signature before copying:

- `dist/play/Moka-Music-Player-Play-upload.aab`
- `dist/play/SHA256SUMS.txt`

If Gradle fails, **do not substitute an unsigned AAB or the GitHub debug APK.** The upload key should never be made available as a public CI artifact. Play will generate Play-signed delivery APKs from your uploaded bundle.

## 4. Publish a privacy policy under the Yoo Shastan brand

Before submission, host an **actual, publicly accessible** page such as `https://yooshastan.com/moka/privacy/`. The URL is reserved as a proposed address in the source; it has **not** been verified as live. Don't submit it until it loads in an incognito browser without a login.

The policy should accurately cover Moka's local audio access, on-device DSP/loudness, optional MusicBrainz/Cover Art Archive metadata queries, AutoEq downloads, optionally saved encrypted Navidrome credentials, user-selected Navidrome servers, log/diagnostic exports, third-party request metadata, local data deletion, and a functioning brand support/contact route. Link the privacy page from **within Moka Settings** and in Play Console. Finalize and review the **Data safety** questionnaire; don't mark "no data collected" without assessing optional internet integrations and library requests.

**Privacy / pseudonym reminder:** Studio branding and `yooshastan.com` avoid a link back to the personal website. However, Play developer verification and account disclosures may require legal information; domain registration, payment processors, and public repositories can also create correlations.

## 5. Play Console content and testing tasks

Complete the store listing (short/full description, screenshots from the real app, 512×512 icon, 1024×500 feature graphic, developer contact) and App content forms (Data safety, privacy, content rating, ads, target audience, app access, and foreground service mediaPlayback declaration). For reviewers, explain that **local music works without login** and optionally provide a functioning demo Navidrome server account if they need to test the network feature—do not share a private personal server.

Upload `dist/play/Moka-Music-Player-Play-upload.aab` to **Internal testing → Create new release**. Opt into Play App Signing, use its pre-launch report and App Bundle Explorer to inspect native 16 KB compatibility and device warnings, then install the Play-distributed test on a phone. Test local playback, Android Auto, USB, DSP, background/lock-screen playback, WMA/FLAC, shuffle, saved login and network reconnection. Read and address all Console warnings.

If this is a **new personal developer account** (created after Nov 13, 2023), complete **Closed testing with at least 12 testers opted in continuously for at least 14 days** and request Production access afterwards. Internal testing alone doesn't fulfill this requirement.

## 6. Before first Production submission

- Verify the **16 KB page-size** requirement with Google Play's app bundle analysis and physical/emulator tests, especially for native C++ libraries.
- Review third-party audio DSP libraries and licenses/attributions in `THIRD_PARTY_NOTICES.md` and `docs/release/`.
- Confirm branding, screenshot permissions, Google Play foreground-service requirements, no prohibited in-app payment links, and tester feedback.
- Plan the migration: a Play-installed package signed by Google's app-signing key may not update a locally/debug-signed `com.mokamusic.player` app in place. Back up user settings and avoid uninstalling before understanding signature compatibility.
- Increase `versionCode` for future Play updates. Never reuse or repoint published release tags.

Official resources:
- Signing https://developer.android.com/studio/publish/app-signing
- App Bundles https://developer.android.com/studio/publish/upload-bundle
- Google Play testing requirements https://support.google.com/googleplay/android-developer/answer/14151465
- Foreground service declaration https://support.google.com/googleplay/android-developer/answer/9214102
- 16 KB page-size support https://developer.android.com/guide/practices/page-sizes
