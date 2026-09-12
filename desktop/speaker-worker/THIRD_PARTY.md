# Speaker analysis components

The optional model download is 47,215,727 bytes. Audio stays on the PC. The app
uses CPU inference through sherpa-onnx; it does not require CUDA, PyTorch, an
account, or a separate Python installation.

| Component | Source and license |
| --- | --- |
| sherpa-onnx / sherpa-onnx-core 1.12.26 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.26), Apache-2.0 |
| Pyannote segmentation 3.0 ONNX export | [Public sherpa-onnx maintainer release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models), MIT, copyright CNRS |
| NeMo TitaNet-S ONNX export | [Public sherpa-onnx maintainer release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models), [NVIDIA model card](https://catalog.ngc.nvidia.com/orgs/nvidia/nemo/models/titanet_small), Apache-2.0 under the [NeMo toolkit license](https://github.com/NVIDIA/NeMo/blob/v1.19.0/LICENSE) |
| NumPy 2.2.6 | [NumPy](https://github.com/numpy/numpy/tree/v2.2.6), BSD-3-Clause; bundled dependency metadata contains its notices |
| PyInstaller bootloader | [PyInstaller](https://pyinstaller.org/en/v6.16.0/license.html), GPL-2.0-or-later with the bootloader distribution exception |

The segmentation archive includes its MIT license, reproduced in
`licenses/pyannote-segmentation-MIT.txt`. This app downloads the public,
MIT-licensed ONNX distribution documented by the sherpa maintainers. It does not
access the gated original Hugging Face checkpoint or use Community-1.
The conversion was performed upstream; this app does not alter the downloaded weights.
`models.json` pins immutable GitHub asset IDs, sizes, archive member and SHA-256
for both source downloads and installed model files. The original segmentation
archive contains the float32 and int8 variants; we install float32 only.

The [official diarization documentation](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html)
describes automatic clustering when the global speaker count is unknown. The
default threshold is 0.9; the user can supply a known count from 1 to 20. This
does not guarantee that 20 people or overlapping voices will be separated
accurately. Labels are local to each recording and can be renamed/corrected.
Names are never guessed from voice identity.
