# Transcription reliability — 1.0.2

This release addresses continuous microphone capture, interrupted Android speech recognition, and repeated transcript storage. The Preview APK updates 1.0.1 Preview in place and preserves its notes, settings, and downloaded models.

## Capture and Stop

The microphone has a dedicated owner thread, separate from the worker that serializes every native model operation. AudioRecord continues to be read during inference. At most 20 half-second PCM chunks (about 640 KB / ten seconds of audio) wait in the queue.

Normal Stop requests microphone shutdown independently of inference. The capture thread drains available nonblocking driver reads, including an incomplete final chunk, before stopping/releasing the microphone. A two-second tail bound prevents an indefinitely readable driver from keeping capture alive. The native worker drains accepted chunks and finalizes before the service finishes saving and stops.

Queue overflow or a tail that exceeds that bound produces a visible error explaining that recent audio could not all be saved. Already accepted audio is processed when the native engine remains usable. A native failure preserves the latest text hypothesis as interrupted. Destroy suppresses callbacks immediately and waits for in-flight native operations to return before freeing their handle.

A bounded queue cannot make an indefinitely slow model keep up with speech. The overflow message recommends a smaller model; performance, battery consumption, device routing, and actual stop timing still require phone measurements. Model loading still precedes microphone capture. There is no audio file for replay or re-transcription.

## Retry recovery

Each Android recognizer utterance has one stable segment ID. Partial results update that segment. A nonblank provider result closes it as FINAL; a failure, empty result, or stop timeout preserves the latest hypothesis as INTERRUPTED. Recovery happens before another recognizer starts. Terminal callbacks immediately invalidate the old provider, preventing late callbacks during retry delays from duplicating or replacing recovered text.

The default Android recognizer can still depend on the network and has gaps between recognition sessions. This release preserves text already received; it cannot recover audio never captured by that provider.

## Transcript storage and summaries

Native cumulative snapshots are converted into stable complete-word pieces plus one replaceable tentative tail, preserving spacing. Stable pieces do not each trigger an immediate summary request. Utterance completion or interruption requests a prompt refresh; intermediate updates remain throttled and coalesced.

New transcripts use a `transcript_segments` table keyed by recording ID and segment ID. PARTIAL revisions update the existing row. FINAL and INTERRUPTED rows are immutable, so stale partials cannot overwrite them. Repeating the same hypothesis does not create another revision. Retained transcript text grows with the transcript, rather than with every previous snapshot.

Summary input includes segment identity, revision, exact text, and uncertainty status. The prompt distinguishes revised text from new speech and warns against treating interrupted hypotheses as confirmed decisions. After a successful response, the daily summary and exact revision acknowledgments are saved in one Room transaction. A newer partial arriving during the AI request remains pending even if timestamps are equal or the clock changes. Segment IDs maintain order within a recording.

Database version 2 uses an explicit, non-destructive migration. Existing notes and v1 transcript rows remain intact; legacy rows gain only an acknowledgment flag so a timestamp-boundary row is not repeatedly summarized. Existing duplicate legacy snapshots are not automatically purged because old data lacks reliable recording/utterance identity. New segments retain their initial date if revised after midnight.

## Verification

- Native host lifecycle checks: 21 scenarios, including slow inference while capture continues, normal-stop queue/tail drain, overload, destruction during inference, partial read recovery, and exact segment reconstruction.
- Android recognizer host checks: 19 scenarios, including network/no-match/timeouts, stable segment IDs, interrupted recovery, and stale callbacks.
- Service host checks: 8 scenarios, including ordered writes before shutdown, complete live display with incremental storage, interrupted text reaching storage before a retry result, and final queue drain.
- App unit tests cover summary scheduling, AI response parsing, and eight storage/formatting regressions.
- The production migration and SQL queries are exercised against SQLite for legacy retention, revision acknowledgment races, interrupted segments, timestamp ties, and date isolation.
- Android compilation, unit tests, lint, and APK assembly are run for the published Preview build.

Host Android classes and the native host engine are test doubles. These checks do not establish real microphone routing, model accuracy, or sustained real-time performance on a phone. English remains the configured language. Speaker labels, audio replay, a transcript-history editor, and an overnight refresh of the Today panel remain outside this release.
