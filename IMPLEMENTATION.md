# LiveMeetingNotes Implementation Plan

## Goal

Build an Android Kotlin app that listens continuously in the background, routes audio from the internal or Bluetooth microphone, creates a live transcript, sends rolling transcript chunks to an AI model for summary + action item extraction, and stores the results locally by date.

## Architecture

- `ForegroundListeningService` keeps recognition alive when the app is backgrounded.
- `SpeechTranscriber` wraps Android `SpeechRecognizer` for near-continuous on-device STT.
- `ConversationOrchestrator` persists transcript chunks and periodically asks DeepSeek/Qwen for structured JSON notes.
- Room stores daily summaries and transcript chunks.
- Compose UI reads the database and service state via `MainViewModel`.

## Verification status

- File scaffold completed.
- Manifest, Room entities, Compose UI, and network integration are wired.
- Build execution is verified on this machine through Android Studio JBR + local Gradle scripts.
