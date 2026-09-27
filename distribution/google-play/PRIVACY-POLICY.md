# Live Meeting Notes — Privacy Policy

Effective date: **September 27, 2026**<br>
Developer: **Oh-my-pi**<br>
Privacy contact: **Venkatasainadh.duppalapudi@gmail.com**

Live Meeting Notes helps you record speech, save transcripts, and optionally generate AI summaries and action items. This policy explains how the Android app handles your information.

## Recording and transcription

The app accesses your microphone when you start recording and grant microphone permission. Recording can continue while the app is in the background, using an Android foreground recording service and notification. You can stop recording from the app or recording notification. Bluetooth permission supports compatible Bluetooth microphones; notification permission supports recording notifications.

There are two speech recognition modes:

- **Android speech:** Your device's speech recognition service processes microphone audio and returns text. Depending on that service and its settings, audio may be sent to its servers. The app saves the transcript, but does not save a replayable audio recording in this mode. The service provider's terms and privacy practices apply.
- **Downloaded on-device models:** The app processes audio on your device using the model you select and saves audio and transcripts in the app's private storage. Downloading a model requires an internet connection. On-device transcription does not send audio to the AI summary providers.

Android's general speech service can use remote processing; choosing Android speech is not a guarantee of offline transcription. See [Android's explanation of its speech recognition service](https://developer.android.com/reference/android/speech/SpeechRecognizer).

## Optional AI summaries

If you save your own AI provider API key, the app automatically sends transcript updates to your selected provider to generate summaries, running context and action items. Requests include transcript text, previous summaries and context, recording and segment identifiers, processing metadata, the selected model and your API key for authentication. The app sends these requests directly to OpenAI, DeepSeek or Alibaba Cloud's Qwen service, according to your selection. It does not send the saved audio file to these summary APIs.

You can use recording and transcription without configuring AI summaries. Once a key is saved, leaving the key field blank keeps that key; this version has no separate disable-AI or remove-key button. Clearing the app's storage in Android settings removes the saved key and all other local app data. Revoking a key through its provider prevents future successful use but does not remove data previously sent to that provider.

The app's connection test sends your API key, selected model and a short test prompt to the selected provider. It does not include your meeting transcript.

Provider handling, retention, account settings and processing locations are governed by the applicable API service terms. We have not established a single retention period that applies to every supported provider and account, and do not promise that requests are never retained or used beyond immediate processing. Consult your provider's applicable API terms and privacy information: [OpenAI](https://openai.com/policies/privacy-policy/), [DeepSeek](https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html), and [Alibaba Cloud](https://www.alibabacloud.com/help/en/legal/latest/alibaba-cloud-international-website-privacy-policy). General privacy pages may be supplemented by API-specific agreements.

## Local storage and Android backups

The app stores transcripts, recording titles and dates, audio metadata, summaries, context and action items locally. Downloaded-model recordings also include audio and word timing information. The app stores your API key in encrypted app preferences protected by an Android cryptographic key. Recordings and the notes database do not have additional app-level encryption.

Depending on your device and backup settings, Android may back up or transfer the app's notes database and speech settings. The database includes transcripts and summaries. The app's backup rules exclude saved audio, downloaded models and the encrypted preferences containing API credentials. Consequently, a restored transcript may not have its original audio available. Android manages backup storage and restoration separately from the app; see [Android backup information](https://developer.android.com/identity/data/autobackup).

## Downloads, exports and network information

Model downloads connect to Hugging Face and its delivery services. These download requests do not include your meeting text or audio. Like other internet services, model hosts and AI providers receive network information needed for the connection, including your IP address; their handling of that information depends on their service practices.

When you choose to share, copy or export content, the selected app, clipboard or storage destination receives that content. Those copies are outside this app's private storage and are subject to the receiving service's practices.

The app's own AI and model-download connections use HTTPS. A device-provided speech recognition service controls its own network processing. The app does not display ads or provide an app account. Optional purchases use Google Play Billing and its supporting Google libraries.

## Retention and deletion

Local notes and recordings remain in the app until its data is removed; the app does not automatically expire them. This version does not include individual recording or note deletion. To remove local recordings, transcripts, summaries, settings and API credentials, use **Android Settings → Apps → Live Meeting Notes → Storage → Clear storage**; the wording varies by device. Removing the app ordinarily removes its private local data, but existing Android backups may be restored when it is installed again.

Clearing local storage does not itself delete existing backup copies, exported files, clipboard copies, or information already sent to external providers. Manage Android backup copies through your device or backup account controls, delete exports where you saved them, and use the relevant provider's privacy or account controls for provider-held data. The developer cannot access and erase private recordings stored only on your device.

For privacy inquiries, contact **Venkatasainadh.duppalapudi@gmail.com**.

## Optional support purchases

Google Play provides product prices, purchase status, identifiers and purchase tokens. The app holds these in memory and sends tokens back to Play to complete repeatable support purchases and recover unfinished payments. This version has no developer billing server and does not save purchase history. Currency comes from the Play account's country; the app does not request location permission for pricing.

Google handles payment details and retains transaction records under its [privacy policy](https://policies.google.com/privacy). The app never receives card numbers or bank details. Google's billing libraries may handle purchase-related service diagnostics. Clearing app storage does not delete Google's transaction records; manage purchases and refunds through Google Play.
