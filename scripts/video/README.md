# Live Meeting Notes — product preview

Two 42-second videos, with captions burned in and an original instrumental soundtrack:

- `Live-Meeting-Notes-landscape.mp4`: 1920 × 1080, for YouTube and the Google Play listing.
- `Live-Meeting-Notes-linkedin.mp4`: 1080 × 1350, for a LinkedIn feed post.

Both use H.264 video, AAC stereo audio, 24 fps, and fast-start MP4. Upload the MP4 directly to LinkedIn. For Google Play, upload the landscape version to YouTube, set visibility to Public or Unlisted, allow embedding, and paste its YouTube URL into the listing's Preview video field.

The app is still being prepared for Google Play. The accompanying `linkedin-post.txt` is a draft only; nothing has been posted on LinkedIn or YouTube.

The actual app UI is captured in GitHub's Android emulator. Meeting names, transcript, waveform, summary, action items, and timing are synthetic demonstration content. Playback uses the real player with a silent audio fixture. The video is a feature walkthrough, not a test of recognition or AI accuracy/latency. Captions disclose sample content and the need for your own provider key for optional AI.

Music was generated from original oscillator synthesis without third-party music, recordings, or voice. See `soundtrack-provenance.txt`.

The existing Play AAB and production app behavior are unchanged. The capture harness lives only in androidTest and is explicitly opt-in.
