# Play Console values — Live Meeting Notes

Use these values for the first Play Console setup.

| Field | Value |
| --- | --- |
| Developer account type | Personal |
| Public developer name | Oh-my-pi |
| App name | Live Meeting Notes |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Support email | Venkatasainadh.duppalapudi@gmail.com |
| Category | Productivity |
| Package name after bundle upload | `com.sainadh.livenotes` |
| Initial track | Internal testing |
| Download price | Free in all selected countries |
| Ads | No — no advertising SDK or ads are present |
| App account/login | None |

## Pricing

Set **Products → App pricing → Free**. Complete the merchant payments profile for optional in-app purchases. Follow [BILLING-SETUP.md](BILLING-SETUP.md) for the four support products and international pricing.

Google allows a paid app to become free. After an app has been offered free, it cannot become paid again under the same package name. The free download does not exempt a new personal account from its testing requirements; follow the current Console dashboard. Add purchase testers under License testing separately from track membership.

## Files to upload

- App bundle: `app-release.aab` from the signed GitHub Actions `live-notes-play-bundle` artifact (1.2.0, code 7)
- Store icon: `assets/play-icon.png`
- Feature graphic: `assets/feature-graphic.png`
- Listing text: `STORE-LISTING.md`
- Privacy policy source: `PRIVACY-POLICY.md`
- Public privacy-policy URL: https://sainadh812.github.io/android-live-notes-nemotron/privacy-policy.html
- Public privacy page source: repository `docs/privacy-policy.html`
- Android UI screenshots: `assets/screenshots/` (from GitHub emulator tests)

Upload the `.aab` file, not the artifact ZIP or development APK.
