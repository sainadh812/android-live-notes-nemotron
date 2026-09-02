package com.sainadh.livenotes.stt

/**
 * Available GGUF quantizations of nvidia/nemotron-3.5-asr-streaming-0.6b,
 * mirrored by handy-computer/transcribe.cpp on HuggingFace. Sizes and WER
 * figures are from docs/models/nemotron-3.5-asr-streaming-0.6b.md in the
 * transcribe.cpp repo (FLEURS test-en, offline att_context_size=[56,13]).
 *
 * Smaller quant = faster inference + smaller download, at a small accuracy
 * cost. Q8_0 is the recommended default (near-reference accuracy, well
 * under half the size of F32).
 */
enum class NemotronQuant(
    val label: String,
    val fileSuffix: String,
    val approxSizeMb: Int,
    val fleursWer: Double
) {
    Q8_0("Q8_0 (best accuracy, 716 MB)", "Q8_0", 716, 7.88),
    Q6_K("Q6_K (593 MB)", "Q6_K", 593, 8.02),
    Q5_K_M("Q5_K_M (534 MB)", "Q5_K_M", 534, 8.15),
    Q4_K_M("Q4_K_M (smallest/fastest, 473 MB)", "Q4_K_M", 473, 8.49);

    val fileName: String get() = "nemotron-3.5-asr-streaming-0.6b-$fileSuffix.gguf"

    val downloadUrl: String
        get() = "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/main/$fileName"

    companion object {
        val default: NemotronQuant = Q8_0
    }
}
