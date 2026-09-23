# Android release validation — 23 September 2026

Release candidate: **LiveMeetingNotes 1.1.1**, version code **6**, package `com.sainadh.livenotes`.

## Completed checks

- Gradle build, release lint, signed AAB and release APK: passed.
- Existing JVM unit tests: **55 passed**, zero failures/errors/skips.
- The Settings screen contains the privacy handling summary, effective date, developer name, and privacy contact supplied for the Play listing.
- Release lint: **zero errors**, 36 warnings, mainly dependency updates and existing code/resource recommendations. Report: `app/build/reports/lint-results-release.html`.
- JNI host contract tests: passed. These cover lifecycle, model families/locales, Unicode, transcript revisions, truncation and long-stream delta handling using a test double.
- Official Google `bundletool` 1.18.3 validation: passed.
- Final AAB manifest: correct production package, target API 36, min API 26, version code 6, not debuggable or test-only.
- AAB configuration declares `PAGE_ALIGNMENT_16K` and contains exactly five arm64 native libraries.
- All five libraries extracted from the final AAB pass LOAD alignment/congruence and GNU_RELRO end alignment checks for 16 KB pages.
- Release APK: `apksigner verify` and `zipalign -c -P 16 4` passed.
- AAB: `jarsigner -verify` reports `jar verified`. All **717** payload entries were additionally read through Java's verifying `JarFile` API; every entry matched the expected upload certificate. ZIP entries are unique and CRC checks passed.
- Store icon: 512×512 RGBA PNG with opaque alpha; feature graphic: 1024×500 RGB PNG. Both were visually inspected. Listing title/short/full descriptions are within Play limits.
- Git diff whitespace checks and native build-script syntax: passed. Signing properties and keystores are excluded from Git.

The AAB verifier reports a self-signed certificate, no timestamp, unsigned filesystem attributes, and streaming-JAR manifest ordering warnings. The upload certificate is self-signed; it is not a public CA certificate. The additional full-entry signature verification above checked the actual ZIP/JAR payload rather than assuming these warnings meant unsigned app content. Acceptance by Play Console has not been tested.

## Changes made for release preparation

- Upgraded compile/target API from 35 to 36 and Android Gradle Plugin to 8.10.1; aligned CI SDK installation.
- Replaced debug-key release signing with private upload-key configuration, and added release guards against preview/emulator variants and missing signing configuration.
- Excluded encrypted credential preferences from Android backup and device transfer. Notes database backup remains enabled.
- Rebuilt `libggml.so` and `libtranscribe.so` from the same pinned upstream commit and NDK with both 16 KB linker flags to repair GNU_RELRO alignment. Preserved all 84/1,463 original exported symbols, respectively, and their library dependencies. Cross-library engine imports resolve. Existing CPU/base libraries and the JNI bridge binary were retained.
- Added a repeatable engine build script and a portable native alignment check run in CI.

## Build environment note

The machine's pre-existing shared Gradle transform cache had missing metadata. A separate cache allowed the build to complete without deleting the shared cache. Successful local commands used:

```sh
GRADLE_USER_HOME=/tmp/live-notes-play-gradle ./gradlew \
  :app:testDebugUnitTest :app:lintRelease :app:bundleRelease :app:assembleRelease \
  --project-cache-dir /tmp/live-notes-play-project-cache \
  --no-daemon --console=plain '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

After the two native library replacements, release lint and bundle/APK packaging were rerun successfully; unchanged Kotlin tests were not repeated. On a fresh machine, use the standard build command in the setup guide after configuring the SDK and signing file.

## Artifact fingerprints

| Artifact | SHA-256 |
| --- | --- |
| AAB | `1c3960ea114627d1bdff644dcd6fb966697fdabff5d2ff9784eadd46ef2f0a74` |
| Direct-install APK | `08c3c9122ed360d94bcdf001535b686c183a7f3916c9295c2fda06630bfdda47` |
| Public upload certificate | `aaf89177c61acd9058e06b8027237c7935a23304d0d3148a7ddcd08f70a96410` |

## Still pending

No Android phone is attached to this workspace. Physical microphone/Bluetooth behavior, Android 16 screen layout, actual-model accuracy/performance, and runtime behavior on a 16 KB phone remain unverified for this release. In particular, inspect custom player/transcript top bars and system navigation spacing under edge-to-edge display.

Public release still requires hosting the prepared privacy page at a public URL, verified Data safety answers, actual phone screenshots, a foreground-service demonstration video, the required closed test, and Play Console review. The owner's store identity, email, India price, policy source, and in-app privacy text are prepared. This release is ready for **internal testing**, not evidence of public-launch approval.
