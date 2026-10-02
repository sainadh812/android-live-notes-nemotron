package com.sainadh.livenotes.gemmaprototype.runtime;

/** Immutable download identity. Sizes and LFS hashes verified against the pinned HF revision. */
public final class ModelSpec {
    private ModelSpec() { }

    public static final String NAME = "Gemma 4 E4B IT";
    public static final String REPOSITORY = "litert-community/gemma-4-E4B-it-litert-lm";
    public static final String REVISION = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da";
    public static final String FILE_NAME = "gemma-4-E4B-it.litertlm";
    public static final long BYTES = 3_659_530_240L;
    public static final String SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0";
    public static final String DOWNLOAD_URL = "https://huggingface.co/" + REPOSITORY
            + "/resolve/" + REVISION + "/" + FILE_NAME + "?download=true";
    public static final String MODEL_CARD_URL = "https://huggingface.co/" + REPOSITORY;
    public static final String LICENSE_URL = "https://ai.google.dev/gemma/terms";
    public static final String RUNTIME_VERSION = "0.17.1";
    public static final int DEFAULT_CONTEXT_TOKENS = 8192;
    public static final int THINKING_TOKEN_BUDGET = 256;
    /** Room for runtime caches; native RAM requirements are separate from this disk reserve. */
    public static final long DISK_RESERVE_BYTES = 1024L * 1024 * 1024;
}
