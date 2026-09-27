# Free app and optional Google Play support purchases

Android 1.2.0, version code 7, package `com.sainadh.livenotes`.

The app is free to download. Settings → Buy me a coffee offers four optional, repeatable one-time purchases. Recording, transcription and playback do not require a purchase. External AI provider usage may still cost money. Successful support displays a thank-you coffee; there is no subscription, permanent paid entitlement, charitable tax receipt, or physical coffee delivery.

**An arbitrary typed payment amount is not implemented:** Play checkout buys predefined products at Console prices, not an amount supplied by the app. The requested four tiers are implemented. Do not show a custom-amount field that promises an unsupported checkout. Supporting exact arbitrary amounts would require a separate payment design and a policy review, not simply another Billing Library parameter.

## 1. Complete your merchant account

Sign in to your existing [Play Console](https://play.google.com/console/) developer account. Open the account's **Settings → Payments profile** and complete the merchant setup, bank verification, and requested tax/identity information. Menu labels can vary. Enter financial details directly into Google's forms.

In the Live Meeting Notes app entry set **Products → App pricing → Free**. This is independent of the optional products below. Once offered free, the same package cannot become a paid download later.

## 2. Build using GitHub Actions

Open the repository's Actions tab and select **Android Play build**. Run it on the branch containing this change.

- Normal runs execute unit tests, lint, native alignment checks and build a development APK with the real production package. The `live-notes-development-apk` artifact is **not** a Play upload. It may conflict with an installed differently signed production app. Export needed data before changing installations.
- For a signed AAB, configure these repository Actions secrets using the **existing** upload key, then run manually with `signed_release` checked:
  - `PLAY_UPLOAD_KEYSTORE_BASE64`: base64 contents of the existing upload keystore.
  - `PLAY_UPLOAD_STORE_PASSWORD`: its store password.
  - `PLAY_UPLOAD_KEY_ALIAS`: its key alias.
  - `PLAY_UPLOAD_KEY_PASSWORD`: its key password.
- The existing key's private location is documented in the release README. Do not paste key material into a chat, commit it, replace it, or include it in artifacts. No secrets have been configured by this change.
- Download `live-notes-play-bundle`, extract `app-release.aab`, and upload that file to **Internal testing**. The workflow does not upload or publish to Play.
- Upload a bundle containing the billing permission before product setup if Console requests it. The old 1.1.1 bundle does not contain this feature. Use a higher version code if 7 has already been uploaded.

## 3. Create exactly these four products

Open **Monetize with Play → Products → One-time products**. Create one product per row. Product IDs are permanent and must match exactly.

| Product ID | Suggested title | US reference price |
| --- | --- | ---: |
| `support_coffee_2` | A little thanks | USD 2.00 |
| `support_coffee_4` | A coffee | USD 4.00 |
| `support_coffee_7` | A generous thanks | USD 7.00 |
| `support_coffee_10` | An extra boost | USD 10.00 |

Suggested description: “An optional virtual coffee to support Live Meeting Notes. Displays a thank-you coffee. All app features remain free. One-time purchase; no subscription.”

For **each** product:

1. Create a **Buy** purchase option with ID **`support`**. The app deliberately selects this option. Use the standard price without a discount offer, rental, or preorder.
2. Set the USD reference price above. Use Console's price conversion to generate regional prices, then check the US price remains exactly the requested dollar amount. If your merchant default currency is INR, use the conversion controls where available and review regional overrides instead of entering the number 2 as an INR amount.
3. Enable India and the supported international countries where you want purchases available. Review INR and the other local prices. Confirm the app's distribution also includes those countries.
4. Save and activate the product/purchase option. “Consumable” behavior is implemented in the app by consuming completed purchases; it is not a subscription. Leave multi-quantity disabled for this four-tier design.

Products/changes can take time to propagate. Missing or inactive products remain disabled in the app, without invented USD or INR fallback prices. Partial availability leaves only the available options enabled.

## 4. How Indian and international prices work

The app uses `ProductDetails` and the selected offer's `formattedPrice` directly. An Indian Google Play account sees Play's INR price. A US account sees the configured dollar price; other supported accounts see the currency returned by Play. This uses the customer's Google Play country, not citizenship, the phone's language, a GPS permission, or an IP lookup in the app.

Regional prices are configured values. Automatic conversion can apply local rounding and tax rules; it is not a live FX conversion at each purchase. The code never performs an exchange-rate calculation or forces INR based on the device locale. Review and maintain prices in Console.

## 5. Test without charging real money

1. Add testers to the internal track and publish its test release; this is not a public production rollout.
2. Separately add the purchasing Google accounts under the developer account's **License testing** settings.
3. Install from the internal-test opt-in link using the intended Play account. If the phone has multiple Google accounts, confirm which one installed the app.
4. Open Settings → Buy me a coffee. Confirm four prices match Console. Use the test payment instrument, not a real card.
5. Test success and buying the same tier again, cancellation, decline, pending payment completing after an app restart, pending cancellation, lost connectivity during completion, unavailable products and returning to the app.
6. Use Play Billing Lab with a license tester to exercise India and US billing countries. Changing the phone's language alone is not a country test. Confirm that the price displayed in the app matches checkout.

Internal-track membership alone does **not** make purchases free. License testers must see Google's test payment options. Never treat a unit test or a successful GitHub build as proof that real Play checkout works.

## Completion and remaining limits

The app consumes completed purchases through Google Play on the client and recovers unfinished purchases when resumed or refreshed. Pending purchases are not consumed. Duplicate callbacks do not produce duplicate consumption within the session. Unknown products are ignored. A changed price requires another deliberate tap. Tokens are not logged or persisted by this app, and no card details reach it.

There is no developer backend or real-time developer notification receiver. If a payment completes while the app is closed, the user needs to reopen the app for completion acknowledgement; Google's acknowledgement deadline still applies. Server verification/acknowledgement would provide stronger fraud and offline recovery protections if this evolves into paid entitlements. This implementation grants no paid recording features or transferable balance.

GitHub unit/UI checks use controlled purchase responses. Live product activation, merchant verification, signed-bundle secrets, real Play country/checkout tests and public release remain owner setup steps. Update Data safety and publish the revised privacy policy before release.

## Official references

- [Product configuration](https://support.google.com/googleplay/android-developer/answer/16430488?hl=en)
- [Currency support](https://support.google.com/googleplay/android-developer/answer/1169947?hl=en)
- [Billing integration and consumption](https://developer.android.com/google/play/billing/integrate)
- [Test purchases and Play Billing Lab](https://developer.android.com/google/play/billing/test)
- [Purchase verification and acknowledgement limits](https://developer.android.com/google/play/billing/security)
- [Payments policy and creator contributions](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en)
