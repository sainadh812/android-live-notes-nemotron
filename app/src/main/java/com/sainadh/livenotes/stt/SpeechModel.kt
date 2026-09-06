package com.sainadh.livenotes.stt

/** Only languages supported out of the box by the bundled model conversions. */
enum class SpeechLanguage(val code: String, val label: String) {
    ENGLISH("en-US", "English (US)"),
    AUTO("auto", "Detect language"),
    ENGLISH_UK("en-GB", "English (UK)"),
    HINDI("hi-IN", "Hindi"),
    SPANISH("es-ES", "Spanish (Spain)"),
    SPANISH_US("es-US", "Spanish (US)"),
    FRENCH("fr-FR", "French"),
    FRENCH_CA("fr-CA", "French (Canada)"),
    GERMAN("de-DE", "German"),
    ITALIAN("it-IT", "Italian"),
    PORTUGUESE_BR("pt-BR", "Portuguese (Brazil)"),
    PORTUGUESE("pt-PT", "Portuguese (Portugal)"),
    DUTCH("nl-NL", "Dutch"),
    TURKISH("tr-TR", "Turkish"),
    RUSSIAN("ru-RU", "Russian"),
    ARABIC("ar-AR", "Arabic"),
    JAPANESE("ja-JP", "Japanese"),
    KOREAN("ko-KR", "Korean"),
    VIETNAMESE("vi-VN", "Vietnamese"),
    UKRAINIAN("uk-UA", "Ukrainian"),
    POLISH("pl-PL", "Polish"),
    SWEDISH("sv-SE", "Swedish"),
    CZECH("cs-CZ", "Czech"),
    NORWEGIAN("nb-NO", "Norwegian Bokmål"),
    DANISH("da-DK", "Danish"),
    BULGARIAN("bg-BG", "Bulgarian"),
    FINNISH("fi-FI", "Finnish"),
    CROATIAN("hr-HR", "Croatian"),
    SLOVAK("sk-SK", "Slovak"),
    MANDARIN("zh-CN", "Mandarin"),
    HUNGARIAN("hu-HU", "Hungarian"),
    ROMANIAN("ro-RO", "Romanian"),
    ESTONIAN("et-EE", "Estonian");
    companion object {
        fun fromCode(code: String?): SpeechLanguage = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/** Immutable downloads compatible with the pinned transcribe.cpp runtime. Sizes are decimal MB. */
enum class SpeechModel(
    val id: String,
    val title: String,
    val description: String,
    val recommendation: String?,
    val repository: String,
    val revision: String,
    val fileName: String,
    val expectedBytes: Long,
    val sha256: String,
    val multilingual: Boolean = false
) {
    MOONSHINE_TINY(
        "moonshine-tiny",
        "Moonshine Tiny",
        "English streaming in a small download. Try this first for short notes. This runtime has a 4,096-token output limit (roughly 17 minutes of speech).",
        "Start here · English",
        "moonshine-streaming-tiny-gguf",
        "85ddff612fa3a2cf40b2f745abcfa90ef82f293b",
        "moonshine-streaming-tiny-Q8_0.gguf",
        50462816L,
        "930e4622ad3a24158b91406c30c977fa6a26b34cb32d6ac3e57cfb23383a869e",
        false
    ),
    NEMOTRON_ENGLISH(
        "nemotron-english",
        "Nemotron English",
        "NVIDIA’s English streaming model, compressed to Q4. A larger option for longer English sessions; performance depends on your phone.",
        "Longer English sessions",
        "nemotron-speech-streaming-en-0.6b-gguf",
        "7d9b719206789e4068d87c6398262ab4dfd4e45d",
        "nemotron-speech-streaming-en-0.6b-Q4_K_M.gguf",
        475436032L,
        "dc959ca31499b114e395c44eb4f0778968f20e5cfb03305a08a39925b2da8e1e",
        false
    ),
    NEMOTRON_Q4(
        "nemotron-q4",
        "Nemotron 3.5 · Compact",
        "Multilingual streaming with 32 supported locales and automatic language detection. The smallest Nemotron 3.5 download.",
        "Multilingual",
        "nemotron-3.5-asr-streaming-0.6b-gguf",
        "6d44e540bc31b0de1dbe174a3cea87f53a7f22fb",
        "nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf",
        495831520L,
        "41c99fa5fb6f3d35f68e79adc3e755eca2232a8d921178bd647b71194792b8fd",
        true
    ),
    NEMOTRON_Q8(
        "nemotron-q8",
        "Nemotron 3.5 · Full",
        "Multilingual streaming with less compressed weights. Uses more storage and memory than Compact; phone accuracy has not been benchmarked.",
        null,
        "nemotron-3.5-asr-streaming-0.6b-gguf",
        "6d44e540bc31b0de1dbe174a3cea87f53a7f22fb",
        "nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf",
        751094240L,
        "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c",
        true
    ),
    NEMOTRON_Q6(
        "nemotron-q6",
        "Nemotron 3.5 · Q6",
        "Multilingual streaming. Keep using this variant if it is already installed, or try Compact to save space.",
        null,
        "nemotron-3.5-asr-streaming-0.6b-gguf",
        "6d44e540bc31b0de1dbe174a3cea87f53a7f22fb",
        "nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf",
        621356512L,
        "4ff802c6207c4a7df23242003fd2aa849a1ab02bba6bc80c3db02e7e82606c28",
        true
    ),
    NEMOTRON_Q5(
        "nemotron-q5",
        "Nemotron 3.5 · Q5",
        "Multilingual streaming. Keep using this variant if it is already installed, or try Compact to save space.",
        null,
        "nemotron-3.5-asr-streaming-0.6b-gguf",
        "6d44e540bc31b0de1dbe174a3cea87f53a7f22fb",
        "nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf",
        559647200L,
        "86429e8c4f7fdcf9b3312269ad1ca6669478ba7805331c4aea7a2e33e9910d65",
        true
    );

    val approxSizeMb: Int get() = ((expectedBytes + 999_999) / 1_000_000).toInt()
    val downloadUrl: String get() = "https://huggingface.co/handy-computer/$repository/resolve/$revision/$fileName"
    val languages: List<SpeechLanguage>
        get() = if (multilingual) SpeechLanguage.entries else listOf(SpeechLanguage.ENGLISH)

    companion object {
        fun fromId(id: String?): SpeechModel? = entries.firstOrNull { it.id == id }
    }
}

data class SpeechSettings(val model: SpeechModel?, val language: SpeechLanguage = SpeechLanguage.ENGLISH)
