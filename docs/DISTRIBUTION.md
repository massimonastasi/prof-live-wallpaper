# Distribution checklist

## Build and release

Use JDK 17, Android SDK 36 and the checked-in Gradle wrapper.

```sh
python3 tools/prepare-assets.py
./gradlew reduceWad testDebugUnitTest lintRelease assembleRelease bundleRelease
```

The downloader verifies the Freedoom 0.13.0 archive and Phase 2 WAD against pinned
SHA-256 values. The wrapper verifies the Gradle distribution. Without
keystore.properties, release output is unsigned: do not distribute it as an official
APK. CI does not receive signing keys.

Before release compare versionCode with all Play tracks and drafts. Test upgrade
from the previous APK, reset, imports, reporting consent, preview, rotation,
backgrounding and process recreation. Verify the certificate against README.
Archive APK, AAB, R8 mapping, checksums and exact source revision.

## Distribution channels

- Play: upload AAB; distinguish upload certificate from Play App Signing.
- GitHub Releases: developer-signed APK and corresponding tagged source.
- F-Droid: build from source; prefer verified reproducible upstream-signed builds.
  A separately signed F-Droid APK is a distinct update channel.
- Self-hosted F-Droid: maintain HTTPS hosting and a signed repository index.
- Galaxy Store: Seller Portal account, Android app review and Samsung device tests.
  This is an Android live wallpaper, not a Galaxy Themes package.
- IzzyOnDroid: admission is conditional on its current gaming/violence and code
  provenance policies. Do not assume acceptance.

Keep package and signing identity stable. Different certificates usually require
uninstalling before changing channels. Never reuse a released versionCode.

## F-Droid preparation

English and Italian Fastlane metadata is in fastlane/metadata/android.
The combined app uses GPL version 2 (GPL-2.0-only for packaging); artwork uses
BSD-3-Clause. See NOTICE for provenance.

Prepare the fdroiddata recipe after the release tag exists. Declare the pinned
Freedoom archive as a verified source input using the mechanism accepted by the
current builder; extract Phase 2 and run reduceWad before building. Do not use
untracked network downloads in the offline build. Validate the recipe using the
actual F-Droid toolchain before submission.

CI checks a clean Linux build, not bit-for-bit reproducibility. Compare independent
unsigned APK builds before enabling upstream signature verification. Never give
maintainers a private signing key.

## Store declarations

No login or automatic analytics. Optional reporting sends previewed diagnostic
details to GitHub through the browser only after the user selects Share with
GitHub. Publishing an issue is a separate public action. Assess this flow using
the current Data safety questionnaire; do not reuse an unqualified no-data-leaves
claim. Link PRIVACY.md from listings. Answer content rating truthfully for pixel
combat and blood; do not market the app as child-directed.

Version 1.2.5 opts out of Android backup and device transfer for all app data.
Test OEM behavior. There is no login state to restore.

## Release gates still requiring separate evidence

Device/OEM and 16 KB testing, memory profiling, Play pre-launch results,
registration of external signing keys, and store reviews. Build success does not
establish these outcomes.

