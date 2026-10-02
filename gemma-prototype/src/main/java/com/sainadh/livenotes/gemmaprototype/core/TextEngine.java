package com.sainadh.livenotes.gemmaprototype.core;

/** Implemented by the phone-local runtime. Tests inject a deterministic fake. */
public interface TextEngine {
    /** Conservative preflight bound; this is not a measured native tokenizer count. */
    int estimateTokenUpperBound(String text) throws Exception;

    default String budgetDescription() {
        return "Conservative UTF-8 byte bound; not a measured token count";
    }

    /** maxOutputTokens includes both thinking and visible answer tokens. */
    String generate(String systemPrompt, String userPrompt, boolean thinking,
                    int maxOutputTokens, Cancellation cancellation) throws Exception;
}
