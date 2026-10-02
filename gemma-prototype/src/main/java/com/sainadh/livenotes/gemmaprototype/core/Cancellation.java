package com.sainadh.livenotes.gemmaprototype.core;

import java.util.concurrent.CancellationException;

@FunctionalInterface
public interface Cancellation {
    Cancellation NONE = () -> false;

    boolean isCancelled();

    default void throwIfCancelled() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Summarization cancelled; completed chunks are saved.");
        }
    }
}
