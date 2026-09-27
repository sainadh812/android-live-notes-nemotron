# Google Play setup for Live Meeting Notes

Updated September 27, 2026 for free download and optional support purchases. This folder prepares the first Android release. No Play Console upload or app publication has been performed from this workspace. The privacy policy is published on GitHub Pages.

Version 1.2.0 (code 7) adds optional Google Play support purchases. Build it with the **Android Play build** GitHub Actions workflow described in [BILLING-SETUP.md](BILLING-SETUP.md). The older local 1.1.1 ZIP/AAB does not contain billing and must not be used for this update.

## First step in Play Console

1. Open [Google Play Console](https://play.google.com/console/), then **Home → Create app**. If you already created the app entry, open that entry instead.
2. Enter **Live Meeting Notes**, default language **English (United States)**, **App**, **Free**, and support email **Venkatasainadh.duppalapudi@gmail.com**. Review Google's declarations and Play App Signing terms, then create the app.
3. Open the app's Dashboard to follow **Set up your app**. Use `STORE-LISTING.md` for the descriptions.

The public developer name is **Oh-my-pi**. Set **Products → App pricing → Free**. Optional in-app purchases still require a merchant payments profile. Enable international distribution and configure the four products' regional prices separately using [BILLING-SETUP.md](BILLING-SETUP.md). A paid app can become free; once offered free it cannot become paid again under the same package. External AI provider charges are separate. See [pricing](https://support.google.com/googleplay/android-developer/answer/6334373?hl=en).

## App bundle and signing

- Production application ID: `com.sainadh.livenotes`.
- Android version: `1.2.0`; version code: `7`.
- Minimum Android: 8/API 26; target: Android 16/API 36.
- Supported native architecture: `arm64-v8a`.
- Build output to upload: `app/build/outputs/bundle/release/app-release.aab`.
- Direct-install release APK for local testing: `app/build/outputs/apk/release/app-release.apk`.

New Play apps use an Android App Bundle (`.aab`). For the first app, use Play App Signing with Google generating/managing the app signing key. This workspace's separate upload key signs the bundle you send to Play; Google signs the installable app delivered to users. See [app signing](https://developer.android.com/studio/publish/app-signing).

The upload key and a private copy of its configuration are stored outside the repository at:

`/home/archgen_guest_1/.local/share/live-meeting-notes/play-upload/`

**Back up that private directory securely before relying on this machine for future releases.** Keep the private keystore and passwords out of Git, the store listing, and public file shares. The root `signing.properties` is ignored by Git; `signing.properties.example` is safe to track. The public upload certificate can be shared when Play requests it. No app API credentials are embedded in the release.

Use GitHub Actions for builds. The existing upload key is configured in the four repository secrets listed in [BILLING-SETUP.md](BILLING-SETUP.md). For future builds, manually run **Android Play build** with `signed_release` checked. The workflow runs tests/lint and publishes the signed AAB as the `live-notes-play-bundle` artifact. Never create a replacement upload key just to make CI pass.

Release builds reject preview and emulator-test flags. Increase `versionCode` for subsequent Play uploads; if code 7 has already been uploaded to this Play entry, increase it before uploading again. Do not upload a Preview/debug APK. A development build with the same package and a different signing key will not accept an in-place release update; export any needed data before changing installations. A Play-signed installation also differs from a directly installed upload-key-signed APK.

## First upload: internal testing

1. In your app, open **Test and release → Testing → Internal testing** (the navigation wording may vary), then create a release.
2. Configure Play App Signing if prompted, and upload the `.aab` above.
3. Enter release notes from `STORE-LISTING.md`, resolve any Console errors, and save/review the internal release.
4. Add your own Google account as a tester, roll out to the internal track, and open the opt-in link on your Android phone using that account.
5. Test microphone permission, Android speech, model download, local recording, audio playback, Bluetooth if supported, background recording with screen off, export/share, and summaries using your own provider key. Verify navigation/system bars and the keyboard on Android 16. Also test on a supported phone with 16 KB memory pages.

Internal testing is the first device check, not a public launch. See [testing tracks](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en).

## Complete before closed testing or public review

- Privacy policy: [public policy](https://sainadh812.github.io/android-live-notes-nemotron/privacy-policy.html). HTTPS access was verified on September 27, 2026. Enter this URL in Play Console. The Android Settings screen includes the policy summary and privacy contact. GitHub Pages serves the `codex/privacy-policy-site` branch; when the source policy changes, update its `privacy-policy.html` and `index.html` there as well.
- Complete Data safety using `DATA-SAFETY-WORKSHEET.md`. Optional third-party AI and Android speech can transmit data off the phone. Provider retention and sharing exceptions need confirmation; do not blindly select “no data collected.”
- Upload the 512×512 icon and 1024×500 feature graphic from `assets/`, plus at least two accurate screenshots from the Android app.
- Complete Ads (no advertising SDK found), target audience, content rating, and any other applicable Console declarations. The owner chooses the intended audience; do not guess the questionnaire responses or claim a rating before completing it.
- Complete App access. Recording/local transcription requires no app login. AI summaries require a provider API key. Give the reviewer a workable, private way to test restricted functionality in the Console instructions; do not put credentials in the listing or repository.
- Complete the foreground microphone service declaration, including a video link. Suggested explanation: “The user starts a recording from the Record screen. The app uses the microphone foreground service to continue the user-requested transcription/recording while the screen is off or another app is in front. An ongoing notification shows the recording state. Stopping the service would interrupt capture and lose part of the conversation.”
- Make a short demonstration video: open Record, grant microphone access, start capture, show the ongoing notification, switch apps or turn the screen off briefly, return, stop recording, and open the saved result. Do not show API keys or private meeting content. Confirm the video can be opened by reviewers.
- Review the third-party software/model redistribution licenses in the repository before public distribution; this preparation has not audited those licenses.

See [review preparation](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en), [user data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), and [foreground-service declaration](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

## Personal account testing gate

If your developer account is a personal account created after November 13, 2023, it needs a closed test with at least **12 testers opted in continuously for 14 days**, followed by an application for production access. Internal testing does not satisfy this gate, and completing the period does not automatically approve production access. The app download is free in every test track. Add billing testers separately under License testing so their support purchases use test payment methods. Check the requirement shown on the Console dashboard. See [Google's testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) and [testing-track pricing](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en-GB).

## Validation and remaining limits

See `VALIDATION.md` for checks actually performed. A successful local bundle build does not establish acceptance by Google Play or working microphone/model behavior on every phone. Complete the real-device tests, policy details, and Console review before public release.
