# License sources

Reviewed against the primary publisher sources on 2026-09-12. The model files
remain under their upstream licenses; the app's source license does not replace
the model licenses.

| Included file | Original source | Applicable model |
| --- | --- | --- |
| `moonshine-original-LICENSE.txt` | [Moonshine's original LICENSE at commit 547d00660e70aa976d506842cb1c851688043024](https://github.com/moonshine-ai/moonshine/blob/547d00660e70aa976d506842cb1c851688043024/LICENSE) | Moonshine Streaming Tiny: **MIT, section 1**. The introductory scope explicitly includes all streaming models. The full original file is retained, including its separate section for unrelated legacy models. |
| `NVIDIA-Open-Model-License.txt` | [NVIDIA Open Model License Agreement, October 24, 2025](https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/) ([publisher PDF](https://www.nvidia.com/content/dam/en-zz/Solutions/license-agreements/enterprise-software/nvidia-open-model-license-agreements-24-10-2025.pdf)) | Nemotron Speech Streaming EN 0.6B |
| `NVIDIA-Trustworthy-AI-Terms.txt` | [NVIDIA Trustworthy AI, June 27, 2024](https://www.nvidia.com/en-us/agreements/trustworthy-ai/terms/) | Incorporated by section 2.3 of the NVIDIA agreement |
| `OpenMDW-1.1.txt` | [OpenMDW License Agreement, version 1.1](https://openmdw.ai/license/1-1/) | All four Nemotron 3.5 variants |

The NVIDIA and OpenMDW text files preserve the complete agreement text from
the linked publisher web pages, with HTML/navigation removed and formatting
represented as plain text. The Moonshine file preserves the original source
text. SHA-256 digests of these checked-in copies are pinned in manifest.json.

## Redistribution requirements retained in this mirror

- MIT requires its copyright and permission notice with copies. Both are
  retained in the full Moonshine license and Notice.txt.
- NVIDIA permits redistribution subject to its agreement. Section 3.1 requires
  a copy of that agreement and the exact NVIDIA attribution in a Notice file;
  both are included. Its incorporated Trustworthy AI terms and the agreement's
  other conditions continue to apply. The mirrored English speech model is not
  a Cosmos model.
- OpenMDW 1.1 permits redistribution with a copy of the agreement and applicable
  copyright/origin notices. Both original and conversion model cards, the
  agreement, and the model authors' attribution are retained.

The pinned cards in manifest.json identify the original model revisions as
f8e9dfd8c562c257c151a907b7b7f2fe8ff8511a (Moonshine),
ef3bf40c90df5cd2de55cc07e06681e03d8e6ee4 (English Nemotron), and
24b151a851dd15909e1fc611b11bb2da52b9fc81 (Nemotron 3.5). Their conversion
publisher is [Handy](https://huggingface.co/handy-computer), whose cards state
that each conversion inherits its original model's license. The publisher's
[conversion documentation](https://github.com/handy-computer/transcribe.cpp/tree/63a44d9239d610b3908e8a66b384924cd4a77217/docs/models)
is supplementary; manifest.json and the pinned original cards are the model
provenance record.
