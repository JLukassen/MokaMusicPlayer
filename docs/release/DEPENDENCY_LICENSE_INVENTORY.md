# Dependency and license inventory

Status: **pre-release audit in progress**

This file records Moka's declared build/runtime dependencies and the evidence that must be checked before a store/commercial release. It is an engineering inventory, not legal advice and not a substitute for reviewing the exact artifacts shipped in the release build.

## Declared application dependencies

The versions below come from `app/build.gradle.kts` on the Beta 2 branch.

| Component | Declared version | Use | Upstream license / status |
| --- | --- | --- | --- |
| AndroidX Compose BOM / UI / Material 3 / Material icons | BOM `2026.09.00` | UI | AndroidX source is published under Apache License 2.0. Verify resolved artifacts/notices in the release dependency graph. |
| AndroidX Activity Compose | `1.13.0` | Activity/Compose integration | Apache License 2.0; verify resolved artifact metadata. |
| AndroidX Lifecycle Runtime / Runtime Compose / ViewModel Compose | `2.11.0` | lifecycle/state | Apache License 2.0; verify resolved artifact metadata. |
| AndroidX Media3 ExoPlayer / Session / Common | `1.11.1` | playback/session | AndroidX / Apache License 2.0; verify resolved artifact metadata. |
| Google Guava (Android) | `33.7.2-android` | futures/utilities | Apache License 2.0. |
| JUnit 4 | `4.13.2` | unit tests only | Eclipse Public License 1.0. Test-only dependency; not expected in the release runtime APK. |

## Build-tool dependencies

These are required to build Moka but are not automatically equivalent to runtime code shipped inside the APK.

| Component | Version |
| --- | --- |
| Android Gradle Plugin | `9.4.0` |
| Kotlin Compose plugin | `2.2.10` |
| Gradle wrapper | `9.6.0` |
| CMake | `3.22.1` |

The final release audit must inspect the generated/resolved dependency graph rather than relying only on this declared list.

## Required final audit

Before a public stable/store release:

1. Run `scripts/release-license-audit.sh`.
2. Archive `build/release-audit/runtime-dependencies.txt` with the release candidate.
3. Inspect every resolved/transitive runtime artifact and its license/NOTICE requirements.
4. Confirm that test-only and build-only components are not being listed as shipped runtime dependencies unless they are actually packaged.
5. Update `THIRD_PARTY_NOTICES.md` with any required copyright/license/NOTICE text.
6. Complete and sign off `docs/release/DSP_SOURCE_PROVENANCE.md`.
7. Do not add a project-wide LICENSE based on assumptions about DSP provenance; choose the project license only after the provenance review is complete.

## Evidence references

- AndroidX source files and published POM metadata identify Apache License 2.0.
- Guava's upstream repository identifies Apache License 2.0.
- JUnit 4's upstream repository identifies Eclipse Public License 1.0.

The exact release artifacts remain the source of truth for the final transitive audit.
