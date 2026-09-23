# Google Play Data safety worksheet — Live Meeting Notes

**WORKING DRAFT — NOT READY TO SUBMIT.** Based on Android source reviewed on 23 September 2026. Package: `com.sainadh.livenotes`. Fill the verification gaps below before entering final answers in Play Console. This is a code-based inventory, not confirmation of external providers' contracts or retention practices.

## Form starting points

| Question or topic | Current evidence / proposed treatment |
| --- | --- |
| Does the app collect data? | **Yes.** Configured AI summaries transmit meeting text and credentials off-device. Do not answer “No” merely because users supply their own keys or can use another mode. |
| App account creation | **No app account system.** External AI provider accounts and API credentials are separate. |
| Advertising | No advertising SDK, advertising identifier access or ad display found. |
| Analytics / crash reporting | No analytics or crash-reporting SDK or upload endpoint found. Android platform diagnostics are separate. |
| In-app purchases | No billing SDK or purchase flow found. Do not infer the intended Play listing price from this. |
| Privacy policy URL | **Pending:** host `PRIVACY-POLICY.md` at a public, stable URL and enter that URL. Developer: Oh-my-pi; privacy email: Venkatasainadh.duppalapudi@gmail.com. |
| Privacy policy inside app | The Android Settings screen includes privacy information and the privacy contact. Add the hosted URL later if a clickable web policy is preferred. |
| Encryption in transit | App-owned AI and download URLs use HTTPS; cleartext is disabled. **Final all-data answer pending** verification of speech-service handling and other applicable flows. |
| Deletion requests | No implemented developer deletion-request mechanism or individual note deletion UI. Android Clear storage removes local app data. Do not claim a working server-side deletion service or equate local clearing with deleting provider/backup copies. |
| Independent security review | None established by this audit. Do not claim certification. |

## Data inventory

Categories below are candidates to reconcile with the exact current Console prompts. “Optional” describes the available feature choice, subject to the caveats that follow.

| Data | Actual handling | Form direction and open verification |
| --- | --- | --- |
| Voice / sound recordings | Android speech gives audio to the device's speech service, which may use remote processing. Downloaded models process and save audio locally. Users may share WAV files themselves. | Review **Audio files → Voice or sound recordings**. Account for the remote speech path. Purpose: app functionality. Confirm whether optional applies to every supported user/device; do not call processing ephemeral without evidence. |
| Transcript text, previous summaries, running context and action items | Stored locally; text, previous summary and context are sent automatically to selected AI provider once a key is saved. Returned summaries/action items are saved locally. | **App activity → Other user-generated content** is the primary candidate. AI collection is optional to configure; purpose: app functionality. Consider any more specific category deliberately extracted from the content. |
| API credential | Encrypted local preference; sent as bearer authentication to selected AI endpoint and connection test. A key can identify the user's external provider account. | Review **Personal info → User IDs** and the Console's other applicable personal-information categories against the actual credential semantics. Purpose: authentication/app functionality. Do not omit credentials because they are encrypted in transit. |
| Recording UUID and segment metadata | Per-recording random UUID, segment number, revision, status and text are included in summary requests. UUID is not a deliberately persistent device identifier. | Disclose with the content flow; assess identifier classification if providers can associate it with an account or device. Do not automatically label a recording ID as an advertising/device ID. |
| IP address and connection metadata | AI, speech and model-host services can receive network metadata. App does not explicitly read GPS or send GPS coordinates. | Verify provider logging and derived uses. Determine relevant identifier/location/diagnostic declarations from actual processing; do not declare location from IP merely by assumption. |
| Recording titles/dates, audio metadata, speech preferences | Saved locally; titles may accompany user exports. Notes database and speech preferences are eligible for Android backup/transfer. | Inventory backup and user-export flows separately; determine applicable disclosure treatment. Local-only access by itself is not off-device collection. |

No direct app access to contacts, calendar, photos, precise location, payment details or advertising IDs was found. Speech and transcripts can nevertheless contain personal information spoken by participants.

## Decisions requiring evidence before submission

1. **Collection versus sharing:** AI providers and the Android speech service receive data. Record whether each recipient acts as a qualifying service provider, or whether the transfer meets a user-directed/disclosure exception. Supplying one's own key alone is insufficient evidence. Leave the final sharing answer open until the actual terms and disclosure flow are verified.
2. **Ephemeral processing:** Provider retention is unknown. There is no evidence that all requests remain only in memory for the duration of a response. Do not select “processed ephemerally” without provider-specific evidence.
3. **Required versus optional:** AI is opt-in by saving a credential; local models offer a transcription alternative. A saved key currently enables automatic summaries with no disable/remove-key control. Verify the optional classification against all supported users and consider adding a direct opt-out before release.
4. **Speech provider:** The app uses generic `SpeechRecognizer.createSpeechRecognizer`, not the on-device-only factory. It neither selects a single provider nor proves that its service is offline. Identify supported device behavior, disclosures and applicable provider practices.
5. **Credentials:** One key is saved for all provider selections. Changing provider while leaving the key blank reuses the saved key with the new provider. Consider provider-specific credentials or clearing the key when switching; update this worksheet if behavior changes.
6. **Deletion and retention:** Local database/audio have no expiration or individual deletion control. Clear storage removes the local copy; prior Android backups may later restore notes, and exports/provider copies remain separate. Verify provider deletion routes before claiming deletion availability.

## Backups and local security — verified implementation

- Notes database, including transcripts, summaries, action items and recording metadata, is eligible for Android cloud backup/device transfer. Speech preferences are included.
- `secure-settings.xml` is explicitly excluded from both legacy backup and current cloud-backup/device-transfer rules. This excludes the encrypted API credential preferences.
- Audio files, word timings and model files are outside the backup include-list. A restored database may reference audio that was not restored.
- API credentials use EncryptedSharedPreferences and Android MasterKey. Audio and Room database have no extra app-level encryption. Do not claim all stored content is encrypted by the app.
- External provider retention, training use, jurisdiction and deletion behavior remain unverified; do not substitute a general consumer privacy policy for applicable API terms.

## Official guidance to use while completing the form

Google treats off-device transmission as collection even when a third party receives it. Local-only processing is outside that definition. Ephemeral transmission still belongs in the form; sharing exceptions and optional collection have specific conditions. Use the current definitions and category list in [Google Play's Data safety instructions](https://support.google.com/googleplay/android-developer/answer/10787469).

Publish a working public privacy-policy web page, identify the developer and privacy contact, explain handling/retention/deletion, and expose the policy within the app as well as in Console. See [Google Play's User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).

Android's general recognizer can send audio to remote servers: [SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer). Backup/restore behavior is documented in [Android Auto Backup](https://developer.android.com/identity/data/autobackup); encrypted preferences must be excluded from backup because their key may not restore: [EncryptedSharedPreferences reference](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences).

Provider review starting points: [OpenAI privacy policy](https://openai.com/policies/privacy-policy/), [DeepSeek privacy policy](https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html), and [Alibaba Cloud privacy policy](https://www.alibabacloud.com/help/en/legal/latest/alibaba-cloud-international-website-privacy-policy). These links do not establish the appropriate API-specific retention or sharing answer.

## Source evidence

Paths below are relative to `app/src/main/java/com/sainadh/livenotes/`, except where prefixed with `app/`.

| Finding | Source |
| --- | --- |
| Speech service / no forced offline setting | `stt/SpeechTranscriber.kt:108`, `:252` |
| On-device model capture / private audio | `stt/NemotronTranscriber.kt:228`, `:246`, `:313`; `service/ForegroundListeningService.kt:191` |
| AI endpoints / request content / key header | `ai/ChatCompletionClient.kt:15`, `:109`, `:180` |
| Automatic summaries / identifiers | `ai/ConversationOrchestrator.kt:23`, `:35`; `ai/TranscriptFormatting.kt:18` |
| Encrypted credentials / blank-key behavior | `data/ApiKeyStore.kt:11`; `MainViewModel.kt:147`; `MainActivity.kt:674` |
| Local database / absent content delete operations | `data/NotesDatabase.kt`; `data/NotesRepository.kt` |
| Sharing and export | `sharing/RecordingSharing.kt:13`; `MainActivity.kt:218` |
| Microphone permission / settings disclosures | `MainActivity.kt:287`, `:589`, `:657` |
| Model requests | `stt/SpeechModel.kt:130`; `stt/ModelDownloadManager.kt:173` |
| SDK inventory | `app/build.gradle.kts`, dependencies block |
| Backup / cleartext policy | `app/src/main/AndroidManifest.xml:23`; `app/src/main/res/xml/backup_rules.xml`; `app/src/main/res/xml/data_extraction_rules.xml`; `app/src/main/res/xml/network_security_config.xml` |

Recheck these documents whenever the release changes providers, permissions, telemetry, backup rules or deletion controls.
