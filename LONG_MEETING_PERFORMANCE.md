# Long-meeting performance

The recorder still feeds 16 kHz audio in half-second chunks and saves PCM progressively. This change targets repeated transcript and archive work that grew throughout a long recording. It does not change the speech models or enable GPU inference.

## Live updates

- JNI transfers only newly committed UTF-8 text plus the current tentative hypothesis. An unchanged text update returns null while audio consumption, timing, and error checks continue normally. Kotlin incrementally assembles words split across updates and validates the final transcript once. The unfinished committed tail is capped at 256 UTF-16 units, preserving surrogate pairs and exact concatenation for text without spaces.
- The service retains all transcript revisions by segment ID, but publishes a recent preview of at most 4,000 characters and 120 segments. Frequent display construction and word-timing layout therefore stay bounded as the meeting grows. Cropped segment timing is omitted instead of assigning incorrect timestamps to its shortened text.
- Copy, share, export, and View full transcript request the complete document on a background dispatcher. The full view is an explicit snapshot with Refresh transcript; recording continues while it is open. Earlier text remains in storage and is included in all full-transcript actions.
- The recording library's database subscriptions are suspended during capture and resume after saving. Cached older recordings remain available; a recording-in-progress message explains when the new recording will appear.
- Native word-timing files are loaded only for the opened recording and cached for that one recording. Plain transcript concatenation no longer allocates unused character-timing arrays.

## Verification and limits

Regression coverage includes 7,200 synthetic half-second updates: bounded live preview size, exact full transcript persistence, partial revisions, interrupted words, Unicode, stable preview snapshots, and linear JNI committed-text transfer. Another 7,200-update case verifies bounded committed text without spaces, using CJK characters and emoji with exact saved-text reconstruction. Archive tests verify that 7,200 updates during capture do not trigger new archive queries, followed by one refresh when capture ends. Android tests cover the full-transcript action from a long-meeting preview alongside playback, migration, and navigation checks.

The native bridge was rebuilt against the existing pinned engine. A real Nemotron English model on the 11-second JFK sample preserves the complete transcript and all 22 decoder-timed words after the delta change.

These tests simulate an hour's update count; they are not an hour-long microphone or responsiveness measurement on the Galaxy S25 Ultra. The upstream engine still maintains and rebuilds its internal transcript history. Moonshine's tentative transcript remains fully revisable, so it is transferred in full when it changes. Those remaining costs, device memory pressure, and frame timing require phone profiling if slowdowns persist.
