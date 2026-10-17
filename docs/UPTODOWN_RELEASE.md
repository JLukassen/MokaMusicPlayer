# Moka Music Player — Uptodown + Yoo Shastan direct distribution

## Status

**Preparation only:** No release APK was uploaded by this branch; there is no verified Uptodown listing or direct APK URL yet. Do not advertise the GitHub Actions debug-signed APK as a production release. Keep the private signing keystore on the developer's Fedora computer.

App: Moka Music Player · Application ID: `com.mokamusic.player` · Version: `4.0.0-beta06` · versionCode: `38` · Android 8.0+ · ARM64 and x86_64.

### Signer continuity matters

The first **public** APK signing key creates the signing identity for all future Uptodown and direct-download versions. The private key **must not be committed, uploaded to GitHub, included in the APK listing, or sent to ChatGPT**. Back up the existing local release keystore (configured through `keystore.properties`).

Moka already has a Play upload key from an earlier AAB build. You **may** use the same key to sign external APKs, provided it is securely controlled, but Google's Play App Signing delivery certificate may differ from that upload key. APKs from Google Play and from Uptodown/direct distribution can then be **incompatible for in-place upgrades**. Never promise cross-store update compatibility without comparing the exact certificates.

## 1. Build and validate a local signed APK

From Fedora, in the Moka repository:

```bash
git fetch origin
git switch uptodown-distribution
git pull --ff-only origin uptodown-distribution

export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
bash scripts/build-uptodown-apk.sh
```

The script requires local `keystore.properties` and Gradle release signing. It:

1. Runs Kotlin unit tests and `:app:assembleRelease`.
2. Validates `com.mokamusic.player`, versionCode 38 and versionName `4.0.0-beta06`.
3. Verifies the APK with Android SDK `apksigner`, rejects known debug certificate names, and checks 16 KB ZIP alignment when `zipalign` is available.
4. Copies the versioned APK to `dist/uptodown/Moka-Music-Player-4.0.0-beta06-android-release.apk` and generates `SHA256SUMS.txt`.

These checks are **necessary but not sufficient**. Test installation on physical hardware, verify native ELF 16 KB alignment separately if applicable, and complete the provenance/license audit.

To inspect the package certificate again (without printing secrets):

```bash
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs \
  dist/uptodown/Moka-Music-Player-4.0.0-beta06-android-release.apk

(cd dist/uptodown && sha256sum -c SHA256SUMS.txt)
```

Check the real signing fingerprint and preserve it in a **private** release log. Compare the fingerprint on future releases. Never use `jarsigner` alone to validate an APK.

## 2. Uptodown developer account and listing

Visit https://www.uptodown.dev/sign-up and register as **Yoo Shastan** if permitted by its publisher profile verification. Avoid public personal account/profile links on the listing. Uptodown may still require nonpublic legal details.

In **Apps → Add new app** upload the actual signed APK from `dist/uptodown/`, not the `.aab` and not the GitHub debug APK. Choose Android, Music & Audio if offered, Moka Music Player, official site `https://yooshastan.com/moka/` **once public and working**, author/publisher Yoo Shastan, development stage **Beta**, and accurately indicate whether it has ads, IAP or subscriptions (current Beta code includes no ads/subscriptions).

Enter the listing copy from `docs/UPTODOWN_LISTING.md`. Uptodown currently specifies:
- Short description no longer than **70 characters**; long description **at least 50 words**.
- A square icon (preferably 512×512 PNG), real app screenshots (ideally four or more), and an optional exact **1024×500** featured image.
- In **Versions → Manage File Licensing**, specify the truthful distribution model (**Free** if completely free). **Do not label Moka as MIT/GPL/open source without first completing the DSP/source provenance review and adopting an appropriate license.**
- Leave the actual package ID and version information unchanged; subsequent updates require a higher version code. Opt to **Disable uploads by Uptodown editors** if you want to control what APK versions they distribute.

Click **Submit for review** only when the technical, privacy, and licensing gates below are complete. Uptodown's editors decide approval; submitting does not make the app live automatically.

Official Uptodown guide: https://support.uptodown.com/hc/en-us/articles/360053260491-How-to-publish-an-app-on-Uptodown

## 3. Ship a brand-only website landing page

The separate `JLukassen/yooshastan.com` website repository contains the public source for `/moka/`. The visitor-facing page intentionally contains **no links to personal GitHub, owner names or the personal portfolio**. It points to the branded `/moka/privacy/` privacy page.

**No APK is linked yet**. Do not replace the waiting text with a fake download URL. When there is a real vetted APK published at a first-party `yooshastan.com` URL (or an accepted Uptodown listing), update the page to link to exactly that URL. If hosting a direct APK, expose a **downloadable .apk file**, the real SHA-256, versionCode, Android compatibility, release notes and certificate fingerprint, and then test the URL in an incognito browser. Prefer first-party/CDN storage for large APKs; GitHub Pages has bandwidth/build limits.

An Uptodown store listing can be linked from the brand site once the published URL is known. If the goal is *no traffic leaving for Github*, keep personal repository/download URLs absent from HTML.

## 4. Review gates before public submission

- [ ] **Third-party licensing:** resolve every pending entry in `docs/release/DSP_SOURCE_PROVENANCE.md`; verify `docs/release/DEPENDENCY_LICENSE_INVENTORY.md` and `THIRD_PARTY_NOTICES.md`. Moka's DSP provenance is currently explicitly marked OPEN. Do not infer provenance from a lack of matching copyright strings.
- [ ] **Privacy:** verify `https://yooshastan.com/moka/privacy/` is publicly reachable; ensure privacy disclosures match this exact released APK and users have a real support contact channel.
- [ ] **Brand:** ensure Uptodown's public author/site fields show Yoo Shastan, not a personal repository link or personal identity. Hosting the source under a publicly attributable GitHub account can still expose identity even if the website has no outgoing links.
- [ ] **Device testing:** test signed release APK (not a debuggable build) on physical Pixel/Samsung as appropriate: library scan, local FLAC/WAV, DSP toggle, USB safety, background playback, Bluetooth and car transitions, Navidrome, local/network shuffle, login persistence and duplicate-delete confirmation.
- [ ] **Compatibility:** verify supported 64-bit architectures and native 16 KB page sizes where applicable.
- [ ] **Store assets:** submit only accurate screenshots from the running app, remove private server URLs and unauthorized copyrighted media; use Moka's icon/feature graphic.
- [ ] **Signatures and migrations:** existing locally installed debug-signed versions may refuse an in-place APK upgrade. Back up user preferences and be careful with uninstalls.
- [ ] **Android developer verification:** separate from Google Play's 12-tester rule, Google is rolling out identity/app registration for non-Play Android distribution on certain certified devices and countries. Confirm eligibility/registration obligations before worldwide distribution.

## 5. After approval

When Uptodown publishes the new app, verify the **actual** Moka listing URL, version number, APK SHA-256 and signing fingerprint. Then add that link to `https://yooshastan.com/moka/`. If also offering direct downloads, upload the **same signed artifact** through your chosen hosting service with matching SHA-256, not a newly signed copy.

Do not claim production release or public availability while these gates are pending.

Key official links:
- Uptodown publishing: https://support.uptodown.com/hc/en-us/articles/360053260491-How-to-publish-an-app-on-Uptodown
- Uptodown app updates: https://support.uptodown.com/hc/en-us/articles/360061457991-How-to-update-an-app-that-you-already-published-on-Uptodown
- Android APK signing: https://developer.android.com/studio/command-line/apksigner
- Android 16 KB page compatibility: https://developer.android.com/guide/practices/page-sizes
