These are unchanged, checksum-verified speech model files for LiveMeetingNotes. Download them here if your network allows GitHub downloads. The files are release assets; they are not stored in Git source history.

In **Windows 1.0.1 or later**, open **Settings → Speech models** and choose **Download from GitHub**. For a browser download, choose **Import .gguf** on the matching model card, select the downloaded file, then choose **Use model**. Import checks the complete file before installing it and keeps the original download.

| Model | File | Size |
| --- | --- | --- |
| Nemotron English | `nemotron-speech-streaming-en-0.6b-Q4_K_M.gguf` | 475 MB |
| Nemotron 3.5 Compact | `nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf` | 496 MB |
| Nemotron 3.5 Full | `nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf` | 751 MB |
| Nemotron 3.5 Q6 | `nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf` | 621 MB |
| Nemotron 3.5 Q5 | `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf` | 560 MB |
| Moonshine Tiny | `moonshine-streaming-tiny-Q8_0.gguf` | 50 MB |

Choose the model you want; you do not need all six. No model replacement is needed if it is already installed and working. Speaker-model downloads already use GitHub and remain available through the app's separate speaker setup.

Original models are by NVIDIA and Moonshine AI; the compatible GGUF conversions are distributed by handy-computer. Full upstream model cards, revision references, licenses, and attribution are included in `model-attribution.zip`. `manifest.json` records the original immutable URLs, sizes, and SHA-256 hashes; `SHA256SUMS` covers the mirrored files and attribution package.

Nemotron English: **Licensed by NVIDIA Corporation under the NVIDIA Open Model License.** The accompanying NVIDIA agreement and notices apply. Nemotron 3.5 uses OpenMDW-1.1; Moonshine Tiny Streaming uses MIT. Retain the accompanying licenses and notices when redistributing these files.
