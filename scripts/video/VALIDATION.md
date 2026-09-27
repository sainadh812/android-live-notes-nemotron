# Product video validation — September 27, 2026

The [GitHub capture/render run](https://github.com/sainadh812/android-live-notes-nemotron/actions/runs/36329889811) succeeded on commit `f7bd0e91499b1e1e7c4ec9e60f2ee23905cd09a2`. The opt-in emulator capture completed its complete UI journey; the app itself was built on GitHub. No production application source changed.

Both final MP4s are exactly 42 seconds, 24 fps, H.264/yuv420p with AAC stereo at 48 kHz. Landscape is 1920 × 1080; LinkedIn is 1080 × 1350. Full video/audio decoding passed without errors, and MP4 metadata precedes media data for progressive playback. The decoded landscape soundtrack measures -22.6 dB mean and -11.4 dB peak; no clipping.

All six scenes in both formats and 12 samples across each encoded video were visually checked for framing, text, real app UI, and sample-data disclosure. The optional AI caveat includes the requirement for the user's own provider key and possible provider charges. The presentation makes no claim of current Play Store availability. The original soundtrack contains no third-party recordings.

The live transcript and playback motion use the actual emulator screen recording; other scenes use actual app captures. Synthetic meeting text, waveform, timings and summary are seeded by the test. Playback uses a silent WAV. This film does not validate recognition or AI performance.

SHA-256:

- Landscape: `1f1f379443bc3abbe93a1684ce18f06c0b0fe3b12c3bb3f11d545bdf59eb9502`
- LinkedIn: `2005ee4972c052b2d4c255e745b7e5fc4f6baa94e7278d2c9a5ba925e0d051e8`
