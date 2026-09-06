# LiveMeetingNotes architecture

`ForegroundListeningService` selects the available transcription engine at the start of a session. It owns the foreground notification, wake lock, and audio route. Session generations reject stale callbacks. On stop, it accepts the engine's final transcript, waits for ordered database writes, then destroys the engine and stops itself. Capture failures remain visible in app state.

`NemotronTranscriber` uses a single worker for all model, microphone, feed, finalize, and destroy operations. Lifecycle state changes cancel pending capture promptly without freeing resources owned by an in-flight native call. Its main-thread callback contract is final transcript, then stopped; destroy suppresses pending callbacks. The JNI bridge initializes every required API structure and turns native failures into descriptive Java exceptions.

`SpeechTranscriber` wraps the OS recognizer with bounded retry handling and generation-checked callbacks. Stopping waits for the final result, with a five-second timeout that retains the latest hypothesis if the provider does not finish. The manifest declares recognition-service visibility for Android 11+.

`ConversationOrchestrator` persists text promptly. A separate application-scope `SummaryScheduler` allows one API request in flight, coalesces pending updates, and throttles partials. Final results promote a pending request. Summaries read all finalized chunks since the prior successful summary plus the newest partial; timestamp ties are included so coalescing cannot skip text. Failed summaries can be retried explicitly.

Room stores daily notes and transcript chunks. Compose observes database and capture/summary error state through `MainViewModel`. Phone recording requires microphone permission; optional notification and Bluetooth grants do not block it, and stop never requests a permission.

Validation commands and device limitations are documented in [README.md](README.md). JNI build pins, hash verification, and native contract checks are documented in [BUILDING.md](app/src/main/cpp/BUILDING.md).
