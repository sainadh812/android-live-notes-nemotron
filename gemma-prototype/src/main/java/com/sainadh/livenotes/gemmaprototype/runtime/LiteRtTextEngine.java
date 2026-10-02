package com.sainadh.livenotes.gemmaprototype.runtime;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.BenchmarkInfo;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.ExperimentalFlags;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.ai.edge.litertlm.ThinkingConfig;
import com.sainadh.livenotes.gemmaprototype.core.Cancellation;
import com.sainadh.livenotes.gemmaprototype.core.TextEngine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Real text-only LiteRT-LM 0.17.1 bridge. All expensive methods run on the caller's worker thread. */
public final class LiteRtTextEngine implements TextEngine, AutoCloseable {
    public enum BackendPreference { GPU, CPU }
    public interface Status { void onStatus(String message); }

    /** Real native measurements, not estimates. -1 means the runtime did not provide the metric. */
    public static final class GenerationStats {
        public final String backend;
        public final boolean thinking;
        public final int maxOutputTokens;
        public final int thinkingTokenBudget;
        public final long elapsedMillis;
        public final int inputTokens;
        public final int generatedTokens;
        public final int totalCachedTokens;
        public final double prefillTokensPerSecond;
        public final double decodeTokensPerSecond;
        public final double firstTokenSeconds;

        GenerationStats(String backend, boolean thinking, int maximum, int thinkingBudget,
                        long elapsed, BenchmarkInfo benchmark, int cachedTokens) {
            this.backend = backend;
            this.thinking = thinking;
            maxOutputTokens = maximum;
            thinkingTokenBudget = thinkingBudget;
            elapsedMillis = elapsed;
            inputTokens = benchmark == null ? -1 : benchmark.getLastPrefillTokenCount();
            generatedTokens = benchmark == null ? -1 : benchmark.getLastDecodeTokenCount();
            totalCachedTokens = cachedTokens;
            prefillTokensPerSecond = benchmark == null ? -1 : benchmark.getLastPrefillTokensPerSecond();
            decodeTokensPerSecond = benchmark == null ? -1 : benchmark.getLastDecodeTokensPerSecond();
            firstTokenSeconds = benchmark == null ? -1 : benchmark.getTimeToFirstTokenInSecond();
        }
    }

    private final Engine engine;
    private final String backendName;
    private final int contextTokens;
    private final long initializationMillis;
    private final ReentrantLock operation = new ReentrantLock();
    private final Object activeLock = new Object();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final List<GenerationStats> stats = new ArrayList<>();
    private final ScheduledExecutorService cancellationWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "gemma-cancellation");
        thread.setDaemon(true);
        return thread;
    });
    private Conversation active;
    private boolean closed;

    private LiteRtTextEngine(Engine engine, String backendName, int contextTokens, long initialized) {
        this.engine = engine;
        this.backendName = backendName;
        this.contextTokens = contextTokens;
        this.initializationMillis = initialized;
    }

    /** Call ModelStore.requireVerifiedModel first. The same model file supports CPU and GPU. */
    public static LiteRtTextEngine load(File model, File cacheDirectory, BackendPreference requested,
                                       int contextTokens, boolean allowCpuFallback,
                                       Cancellation cancel, Status status) throws Exception {
        if (contextTokens < 2048 || contextTokens > 8192) {
            throw new IllegalArgumentException("Prototype context must be between 2048 and 8192 tokens.");
        }
        if (!model.isFile() || model.length() != ModelSpec.BYTES) {
            throw new IOException("A verified Gemma 4 E4B model is required.");
        }
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Cannot create the runtime cache directory.");
        }
        cancel.throwIfCancelled();
        ExperimentalFlags.INSTANCE.setEnableBenchmark(true);
        long started = System.nanoTime();
        String actualBackend = requested.name();
        Engine engine;
        try {
            notify(status, "Loading Gemma on " + actualBackend + "…");
            engine = initialize(model, cacheDirectory, requested, contextTokens);
        } catch (RuntimeException failure) {
            cancel.throwIfCancelled();
            if (requested != BackendPreference.GPU || !allowCpuFallback) throw failure;
            notify(status, "GPU initialization failed; trying the CPU. This may be slower.");
            actualBackend = "CPU (GPU initialization fallback)";
            try {
                engine = initialize(model, cacheDirectory, BackendPreference.CPU, contextTokens);
            } catch (RuntimeException cpuFailure) {
                cpuFailure.addSuppressed(failure);
                throw cpuFailure;
            }
        }
        try {
            cancel.throwIfCancelled();
        } catch (CancellationException cancelled) {
            engine.close();
            throw cancelled;
        }
        notify(status, "Gemma ready on " + actualBackend + ". Transcript stays on this phone.");
        return new LiteRtTextEngine(engine, actualBackend, contextTokens,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static Engine initialize(File model, File cacheDirectory, BackendPreference requested,
                                     int contextTokens) {
        Backend backend = requested == BackendPreference.GPU ? new Backend.GPU() : new Backend.CPU(4, null);
        // null vision/audio disables those executors. maxNumTokens bounds the full KV cache.
        Engine value = new Engine(new EngineConfig(model.getAbsolutePath(), backend,
                null, null, contextTokens, null, cacheDirectory.getAbsolutePath()));
        try {
            value.initialize();
            return value;
        } catch (RuntimeException | Error failed) {
            if (value.isInitialized()) value.close();
            throw failed;
        }
    }

    /**
     * Conservative UTF-8 byte bound, NOT an exact tokenizer count. The published Java API has no
     * standalone tokenizer: Conversation.getTokenCount measures already-computed KV state only.
     * Byte fallback tokenizers cannot emit more ordinary text tokens than UTF-8 bytes. The pipeline
     * separately reserves prompt-template overhead. Actual native counts are stored in stats.
     */
    @Override public int estimateTokenUpperBound(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** maxOutputTokens includes thinking AND the final answer, as defined by LiteRT-LM 0.17.1. */
    @Override public String generate(String systemPrompt, String userPrompt, boolean thinking,
                                     int maxOutputTokens, Cancellation cancellation) throws Exception {
        operation.lockInterruptibly();
        ScheduledFuture<?> watch = null;
        Conversation conversation = null;
        try {
            if (closed || closing.get()) throw new IllegalStateException("Gemma has been closed.");
            cancellation.throwIfCancelled();
            cancelRequested.set(false);
            int thinkingBudget = thinking ? Math.min(ModelSpec.THINKING_TOKEN_BUDGET, maxOutputTokens / 3) : 0;
            if (maxOutputTokens < 32 || maxOutputTokens >= contextTokens) {
                throw new IllegalArgumentException("Invalid generation token budget.");
            }
            // This repeats the pipeline check at the native boundary, including template reserve.
            long conservativeInput = (long) estimateTokenUpperBound(systemPrompt)
                    + estimateTokenUpperBound(userPrompt) + 256;
            if (conservativeInput + maxOutputTokens > contextTokens) {
                throw new IllegalArgumentException("Prompt and output exceed the configured context budget.");
            }
            ThinkingConfig thinkingConfig = new ThinkingConfig(thinking, thinkingBudget);
            ConversationConfig config = new ConversationConfig(
                    Contents.Companion.of(systemPrompt), Collections.emptyList(), Collections.emptyList(),
                    new SamplerConfig(1, 1.0, 0.0, 0), false, null, Collections.emptyMap(),
                    null, false, maxOutputTokens, thinkingConfig, false);
            conversation = engine.createConversation(config);
            synchronized (activeLock) { active = conversation; }
            Thread generationThread = Thread.currentThread();
            watch = cancellationWatcher.scheduleWithFixedDelay(() -> {
                if (cancellation.isCancelled() || cancelRequested.get() || closing.get()
                        || generationThread.isInterrupted()) {
                    cancelActive();
                }
            }, 0, 150, TimeUnit.MILLISECONDS);
            cancellation.throwIfCancelled();
            long started = System.nanoTime();
            // Synchronous JNI call returns only when inference stops; close() holds the same operation
            // lock, and cancellation merely signals the live conversation from a separate thread.
            Message response = conversation.sendMessage(userPrompt);
            cancellation.throwIfCancelled();
            if (cancelRequested.get()) throw new CancellationException("Gemma generation cancelled.");
            BenchmarkInfo benchmark = null;
            int cachedTokens = -1;
            try { benchmark = conversation.getBenchmarkInfo(); } catch (RuntimeException ignored) { }
            try { cachedTokens = conversation.getTokenCount(); } catch (RuntimeException ignored) { }
            GenerationStats measurement = new GenerationStats(backendName, thinking, maxOutputTokens,
                    thinkingBudget, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    benchmark, cachedTokens);
            synchronized (stats) { stats.add(measurement); }
            StringBuilder text = new StringBuilder();
            for (Content content : response.getContents().getContents()) {
                if (content instanceof Content.Text) text.append(((Content.Text) content).getText());
            }
            // Message.channels contains reasoning and is intentionally neither shown nor saved.
            if (text.toString().trim().isEmpty()) {
                throw new IOException("Gemma returned no final answer. Try without thinking or with a larger output budget.");
            }
            if (measurement.generatedTokens >= maxOutputTokens) {
                throw new IOException("Gemma reached the output limit. The incomplete answer was not saved; use a larger output budget.");
            }
            return text.toString().trim();
        } finally {
            try {
                if (watch != null) watch.cancel(false);
                synchronized (activeLock) {
                    active = null;
                    if (conversation != null) conversation.close();
                }
            } finally {
                operation.unlock();
            }
        }
    }

    public String getBackendName() { return backendName; }
    public int getContextTokens() { return contextTokens; }
    public long getInitializationMillis() { return initializationMillis; }
    public String getTokenCountingMethod() { return "conservative UTF-8 byte bound; native counts measured after generation"; }
    public List<GenerationStats> getGenerationStats() {
        synchronized (stats) { return Collections.unmodifiableList(new ArrayList<>(stats)); }
    }

    /** Safe to call from the UI; does not release resources while JNI still uses them. */
    public void cancel() {
        cancelRequested.set(true);
        // The watcher signals native code on its own thread, keeping the UI responsive.
    }

    private void cancelActive() {
        synchronized (activeLock) {
            if (active != null) active.cancelProcess();
        }
    }

    /** Call off the UI thread. Waits for active inference before deleting native resources. */
    @Override public void close() {
        closing.set(true);
        cancel();
        operation.lock();
        try {
            if (!closed) {
                closed = true;
                cancellationWatcher.shutdownNow();
                engine.close();
            }
        } finally {
            operation.unlock();
        }
    }

    private static void notify(Status listener, String message) {
        if (listener != null) listener.onStatus(message);
    }
}
