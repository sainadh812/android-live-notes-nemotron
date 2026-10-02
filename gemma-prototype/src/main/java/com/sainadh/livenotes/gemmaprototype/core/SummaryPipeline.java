package com.sainadh.livenotes.gemmaprototype.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, phone-local extraction -> recursive evidence reduction -> final summary.
 * Source IDs are validated, not a guarantee that the model's statements are factual.
 * No transcript tail is silently discarded when the context is too small.
 */
public final class SummaryPipeline {
    private static final Gson JSON = new Gson();
    private static final int FORMAT_RETRY_RESERVE = 192;
    private static final String FORMAT_RETRY_REMINDER = "\nFORMAT RETRY: Return a complete valid JSON object in the required schema. "
            + "Use only supplied source IDs. No prose or code fences.";
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(
            "fact", "decision", "action", "open_question"));
    private static final Pattern LINES = Pattern.compile("[^\\r\\n]+(?:\\r\\n|[\\r\\n]|$)");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\s*\\[?((?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?)\\]?(?:\\s|$)");

    public static final class Options {
        public String modelFingerprint = "";
        public String runtimeIdentity = "litert-lm";
        public int contextWindowTokens = 8192;
        /** Total generation budget: thinking plus visible output. */
        public int maxOutputTokens = 2048;
        public int promptTemplateReserveTokens = 256;
        public int overlapSources = 1;
        public int maxMergeRounds = 8;
        public boolean thinking = false;

        private void validate() {
            if (modelFingerprint == null || modelFingerprint.trim().isEmpty())
                throw new IllegalArgumentException("Set the verified model fingerprint before summarizing.");
            if (contextWindowTokens < 512 || maxOutputTokens < 64 || promptTemplateReserveTokens < 64
                    || maxOutputTokens + (long) promptTemplateReserveTokens >= contextWindowTokens)
                throw new IllegalArgumentException("Invalid context, total output, or prompt-template budget.");
            if (overlapSources < 0 || overlapSources > 4 || maxMergeRounds < 1 || maxMergeRounds > 16)
                throw new IllegalArgumentException("Invalid overlap or merge-round limit.");
        }
    }

    public interface ProgressListener {
        void onProgress(String stage, int completed, int total, boolean loadedFromCheckpoint);
    }

    public static final class Source {
        public String id;
        public String text;
        public String timestamp;
        public int startOffset;
        public int endOffset;

        Source(String id, String text, String timestamp, int startOffset, int endOffset) {
            this.id = id;
            this.text = text;
            this.timestamp = timestamp;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public String label() {
            return timestamp == null ? id : id + " @ " + timestamp;
        }
    }

    public static final class EvidenceItem {
        public String kind;
        public String text;
        public List<String> sources;

        public EvidenceItem(String kind, String text, List<String> sources) {
            this.kind = kind;
            this.text = text;
            this.sources = sources;
        }
    }

    public static final class Result {
        public final String markdown;
        public final List<Source> sources;
        public final List<EvidenceItem> items;
        public final String transcriptSha256;
        public final String checkpointDirectory;
        public final String budgetDescription;
        public final int generatedCalls;
        public final int cacheHits;
        public final int chunkCount;
        public final boolean finalThinking;

        Result(String markdown, List<Source> sources, List<EvidenceItem> items, String transcriptSha256,
               File checkpointDirectory, String budgetDescription, int generatedCalls, int cacheHits, int chunkCount,
               boolean finalThinking) {
            this.markdown = markdown;
            this.sources = Collections.unmodifiableList(sources);
            this.items = Collections.unmodifiableList(items);
            this.transcriptSha256 = transcriptSha256;
            this.checkpointDirectory = checkpointDirectory.getAbsolutePath();
            this.budgetDescription = budgetDescription;
            this.generatedCalls = generatedCalls;
            this.cacheHits = cacheHits;
            this.chunkCount = chunkCount;
            this.finalThinking = finalThinking;
        }
    }

    private static final class Stats { int generatedCalls; int cacheHits; }

    public Result run(String transcript, File checkpointRoot, Options options, TextEngine engine,
                      Cancellation cancellation, ProgressListener listener) throws Exception {
        if (transcript == null || transcript.trim().isEmpty())
            throw new IllegalArgumentException("The transcript is empty.");
        if (checkpointRoot == null || options == null || engine == null || cancellation == null)
            throw new IllegalArgumentException("Missing pipeline configuration.");
        options.validate();
        cancellation.throwIfCancelled();
        ProgressListener progress = listener == null ? (stage, done, total, cached) -> {} : listener;
        String transcriptHash = sha256(transcript);
        // Extraction and merge checkpoints can be reused when comparing final-stage reasoning.
        String identity = sha256(SummaryPrompts.VERSION + "\n" + transcriptHash + "\n" + sharedSettings(options));
        File directory = new File(checkpointRoot, identity);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create checkpoint directory.");

        List<Source> sources = splitSources(transcript, options, engine, cancellation);
        List<List<Source>> chunks = chunkSources(sources, options, engine, cancellation);
        Map<String, Source> sourceById = new LinkedHashMap<>();
        for (Source source : sources) sourceById.put(source.id, source);
        Stats stats = new Stats();
        List<EvidenceItem> evidence = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            cancellation.throwIfCancelled();
            List<Source> chunk = chunks.get(i);
            Set<String> allowed = new LinkedHashSet<>();
            for (Source source : chunk) allowed.add(source.id);
            int priorHits = stats.cacheHits;
            evidence.addAll(generateValidated("extract-" + i, SummaryPrompts.MAP, sourcePrompt(chunk),
                    allowed, directory, options, engine, cancellation, stats));
            progress.onProgress("Extracting evidence", i + 1, chunks.size(), stats.cacheHits > priorHits);
        }
        evidence = deduplicate(evidence);

        int round = 0;
        while (!evidence.isEmpty() && !fits(SummaryPrompts.FINAL, evidencePrompt(evidence), options, engine)) {
            cancellation.throwIfCancelled();
            if (++round > options.maxMergeRounds)
                throw new IllegalStateException("Evidence is still too large after bounded merging. "
                        + "Completed chunks are saved; use a larger context or a more concise generation budget.");
            List<List<EvidenceItem>> groups = groupEvidence(evidence, options, engine, cancellation);
            List<EvidenceItem> reduced = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                List<EvidenceItem> group = groups.get(i);
                int priorHits = stats.cacheHits;
                reduced.addAll(generateValidated("merge-" + round + "-" + i, SummaryPrompts.MERGE,
                        evidencePrompt(group), referencedIds(group), directory, options, engine, cancellation, stats));
                progress.onProgress("Merging evidence (round " + round + ")", i + 1, groups.size(),
                        stats.cacheHits > priorHits);
            }
            reduced = deduplicate(reduced);
            if (engine.estimateTokenUpperBound(evidencePrompt(reduced)) >= engine.estimateTokenUpperBound(evidencePrompt(evidence)))
                throw new IllegalStateException("The model did not reduce the evidence to fit. "
                        + "Completed chunks are saved; no transcript text was dropped.");
            evidence = reduced;
        }

        List<EvidenceItem> finalItems;
        if (evidence.isEmpty()) {
            finalItems = Collections.emptyList();
        } else {
            int priorHits = stats.cacheHits;
            finalItems = generateValidated("final", SummaryPrompts.FINAL, evidencePrompt(evidence),
                    referencedIds(evidence), directory, options, engine, cancellation, stats);
            progress.onProgress("Writing final summary", 1, 1, stats.cacheHits > priorHits);
        }
        cancellation.throwIfCancelled();
        String markdown = render(finalItems, sourceById);
        atomicWrite(new File(directory, "summary.md"), markdown);
        atomicWrite(new File(directory, "sources.json"), JSON.toJson(sources));
        return new Result(markdown, sources, finalItems, transcriptHash, directory, engine.budgetDescription(),
                stats.generatedCalls, stats.cacheHits, chunks.size(), options.thinking);
    }

    /** Stable source spans cover every non-whitespace character, including oversized sentences. */
    List<Source> splitSources(String transcript, Options options, TextEngine engine, Cancellation cancel) throws Exception {
        List<Source> result = new ArrayList<>();
        Matcher lines = LINES.matcher(transcript);
        while (lines.find()) {
            cancel.throwIfCancelled();
            String line = lines.group();
            Matcher timestampMatch = TIMESTAMP.matcher(line);
            String timestamp = timestampMatch.find() ? timestampMatch.group(1) : null;
            BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.US);
            sentences.setText(line);
            int begin = sentences.first();
            for (int end = sentences.next(); end != BreakIterator.DONE; begin = end, end = sentences.next()) {
                int cursor = lines.start() + begin;
                int absoluteEnd = lines.start() + end;
                while (cursor < absoluteEnd) {
                    cancel.throwIfCancelled();
                    if (transcript.substring(cursor, absoluteEnd).trim().isEmpty()) break;
                    String id = String.format(Locale.ROOT, "S%04d", result.size() + 1);
                    int acceptedEnd = fittingEnd(transcript, cursor, absoluteEnd, id, timestamp, options, engine);
                    if (acceptedEnd <= cursor)
                        throw new IllegalArgumentException("Context is too small for the extraction prompt and one source character.");
                    // Prefer whitespace near the end; otherwise preserve surrogate pairs when splitting a long word.
                    if (acceptedEnd < absoluteEnd) {
                        int lower = cursor + (acceptedEnd - cursor) / 2;
                        for (int candidate = acceptedEnd; candidate > lower; candidate--) {
                            if (Character.isWhitespace(transcript.charAt(candidate - 1))) {
                                acceptedEnd = candidate;
                                break;
                            }
                        }
                    }
                    result.add(new Source(id, transcript.substring(cursor, acceptedEnd).trim(), timestamp, cursor, acceptedEnd));
                    cursor = acceptedEnd;
                }
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("The transcript has no text.");
        return result;
    }

    private int fittingEnd(String text, int begin, int end, String id, String timestamp,
                           Options options, TextEngine engine) throws Exception {
        Source entire = new Source(id, text.substring(begin, end).trim(), timestamp, begin, end);
        if (fits(SummaryPrompts.MAP, sourcePrompt(Collections.singletonList(entire)), options, engine)) return end;
        int low = begin;
        int high = end;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            int safe = safeBoundary(text, middle);
            if (safe <= begin) { low = middle; continue; }
            Source candidate = new Source(id, text.substring(begin, safe).trim(), timestamp, begin, safe);
            if (fits(SummaryPrompts.MAP, sourcePrompt(Collections.singletonList(candidate)), options, engine)) low = middle;
            else high = middle - 1;
        }
        int safe = safeBoundary(text, low);
        if (safe <= begin) return begin;
        Source candidate = new Source(id, text.substring(begin, safe).trim(), timestamp, begin, safe);
        return fits(SummaryPrompts.MAP, sourcePrompt(Collections.singletonList(candidate)), options, engine) ? safe : begin;
    }

    private static int safeBoundary(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index)) ? index - 1 : index;
    }

    List<List<Source>> chunkSources(List<Source> sources, Options options, TextEngine engine,
                                    Cancellation cancel) throws Exception {
        List<List<Source>> chunks = new ArrayList<>();
        int next = 0;
        while (next < sources.size()) {
            cancel.throwIfCancelled();
            List<Source> chunk = new ArrayList<>();
            int overlapBegin = Math.max(0, next - options.overlapSources);
            for (int i = overlapBegin; i < next; i++) chunk.add(sources.get(i));
            chunk.add(sources.get(next));
            while (chunk.size() > 1 && !fits(SummaryPrompts.MAP, sourcePrompt(chunk), options, engine)) chunk.remove(0);
            if (!fits(SummaryPrompts.MAP, sourcePrompt(chunk), options, engine))
                throw new IllegalStateException("A source exceeds the context budget; no text was truncated.");
            next++;
            while (next < sources.size()) {
                chunk.add(sources.get(next));
                if (!fits(SummaryPrompts.MAP, sourcePrompt(chunk), options, engine)) {
                    chunk.remove(chunk.size() - 1);
                    break;
                }
                next++;
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<List<EvidenceItem>> groupEvidence(List<EvidenceItem> evidence, Options options,
                                                 TextEngine engine, Cancellation cancel) throws Exception {
        List<List<EvidenceItem>> groups = new ArrayList<>();
        List<EvidenceItem> current = new ArrayList<>();
        for (EvidenceItem item : evidence) {
            cancel.throwIfCancelled();
            current.add(item);
            if (!fits(SummaryPrompts.MERGE, evidencePrompt(current), options, engine)) {
                current.remove(current.size() - 1);
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<>();
                current.add(item);
                if (!fits(SummaryPrompts.MERGE, evidencePrompt(current), options, engine))
                    throw new IllegalStateException("One evidence item exceeds the merge budget. "
                            + "Use a shorter generation limit; no source text was discarded.");
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private List<EvidenceItem> generateValidated(String stage, String system, String user, Set<String> allowed,
                                                File directory, Options options, TextEngine engine,
                                                Cancellation cancel, Stats stats) throws Exception {
        cancel.throwIfCancelled();
        if (!fits(system, user, options, engine))
            throw new IllegalStateException("Prompt exceeds its reserved context budget; generation was not started.");
        boolean thinking = options.thinking && "final".equals(stage);
        String requestHash = sha256(stage + "\n" + system + "\n" + user + "\n" + sharedSettings(options)
                + "\nfinalThinking=" + thinking);
        File checkpoint = new File(directory, stage + "-" + requestHash + ".json");
        if (checkpoint.isFile()) {
            try {
                if (checkpoint.length() > 4_000_000L) throw new IOException("Oversized checkpoint.");
                JsonObject stored = JsonParser.parseString(new String(Files.readAllBytes(checkpoint.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!requestHash.equals(stored.get("requestHash").getAsString())) throw new IOException("Checkpoint identity mismatch.");
                List<EvidenceItem> cached = parseEvidence(stored.get("response").getAsString(), allowed);
                stats.cacheHits++;
                return cached;
            } catch (Exception invalidCache) {
                // Corrupt or incompatible cache entries are regenerated; the transcript stays intact.
                cancel.throwIfCancelled();
            }
        }
        String response = engine.generate(system, user, thinking, options.maxOutputTokens, cancel);
        cancel.throwIfCancelled();
        stats.generatedCalls++;
        List<EvidenceItem> items;
        try {
            items = parseEvidence(response, allowed);
        } catch (IllegalArgumentException invalidFormat) {
            // One format-only retry sees the complete original source; no repair by guessing at a truncated answer.
            cancel.throwIfCancelled();
            String retrySystem = system + FORMAT_RETRY_REMINDER;
            if (!fitsGeneration(retrySystem, user, options, engine, 0))
                throw new IllegalStateException("The format retry does not fit the context budget. "
                        + "Completed chunks are saved; no source text was truncated.", invalidFormat);
            response = engine.generate(retrySystem, user, thinking, options.maxOutputTokens, cancel);
            cancel.throwIfCancelled();
            stats.generatedCalls++;
            try {
                items = parseEvidence(response, allowed);
            } catch (IllegalArgumentException stillInvalid) {
                stillInvalid.addSuppressed(invalidFormat);
                throw stillInvalid;
            }
        }
        JsonObject stored = new JsonObject();
        stored.addProperty("requestHash", requestHash);
        stored.addProperty("response", response);
        atomicWrite(checkpoint, JSON.toJson(stored));
        return items;
    }

    static List<EvidenceItem> parseEvidence(String raw, Set<String> allowed) {
        if (raw == null || raw.length() > 4_000_000)
            throw new IllegalArgumentException("Model returned an empty or oversized response.");
        String response = raw.trim();
        if (response.startsWith("```json") && response.endsWith("```")) response = response.substring(7, response.length() - 3).trim();
        else if (response.startsWith("```") && response.endsWith("```")) response = response.substring(3, response.length() - 3).trim();
        try {
            JsonElement parsed = JsonParser.parseString(response);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object.");
            JsonElement itemElement = parsed.getAsJsonObject().get("items");
            if (itemElement == null || !itemElement.isJsonArray()) throw new IllegalArgumentException("Missing evidence items.");
            JsonArray array = itemElement.getAsJsonArray();
            if (array.size() > 2048) throw new IllegalArgumentException("Too many evidence items.");
            List<EvidenceItem> result = new ArrayList<>();
            for (JsonElement entry : array) {
                if (!entry.isJsonObject()) throw new IllegalArgumentException("Invalid evidence item.");
                JsonObject item = entry.getAsJsonObject();
                String kind = requiredString(item, "kind");
                String text = requiredString(item, "text").trim();
                if (!KINDS.contains(kind) || text.isEmpty()) throw new IllegalArgumentException("Invalid evidence kind or blank statement.");
                JsonElement sourceElement = item.get("sources");
                if (sourceElement == null || !sourceElement.isJsonArray() || sourceElement.getAsJsonArray().isEmpty())
                    throw new IllegalArgumentException("Every statement needs supporting source IDs.");
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (JsonElement source : sourceElement.getAsJsonArray()) {
                    if (!source.isJsonPrimitive() || !source.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Invalid source ID.");
                    String id = source.getAsString();
                    if (!allowed.contains(id)) throw new IllegalArgumentException("Model cited an unknown source ID: " + id);
                    ids.add(id);
                }
                result.add(new EvidenceItem(kind, text, new ArrayList<>(ids)));
            }
            return deduplicate(result);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Model response failed the evidence JSON/source-ID checks. "
                    + "Completed chunks are saved. " + malformed.getMessage(), malformed);
        }
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Missing text field: " + name);
        return value.getAsString();
    }

    private static String sharedSettings(Options options) {
        JsonObject settings = JSON.toJsonTree(options).getAsJsonObject();
        settings.remove("thinking");
        return settings.toString();
    }

    boolean fits(String system, String user, Options options, TextEngine engine) throws Exception {
        return fitsGeneration(system, user, options, engine, FORMAT_RETRY_RESERVE);
    }

    private boolean fitsGeneration(String system, String user, Options options, TextEngine engine,
                                   int retryReserve) throws Exception {
        int systemBound = engine.estimateTokenUpperBound(system);
        int userBound = engine.estimateTokenUpperBound(user);
        if (systemBound < 0 || userBound < 0) throw new IllegalStateException("Invalid token-budget estimate.");
        long required = (long) systemBound + userBound + options.promptTemplateReserveTokens
                + options.maxOutputTokens + retryReserve;
        return required <= options.contextWindowTokens;
    }

    private static String sourcePrompt(List<Source> sources) {
        StringBuilder result = new StringBuilder("Transcript source records (chronological):\n");
        for (Source source : sources) {
            result.append('[').append(source.id);
            if (source.timestamp != null) result.append(" | ").append(source.timestamp);
            result.append("] ").append(source.text).append('\n');
        }
        return result.toString();
    }

    private static String evidencePrompt(List<EvidenceItem> items) {
        return "Evidence from chronological transcript chunks:\n" + JSON.toJson(items);
    }

    private static Set<String> referencedIds(List<EvidenceItem> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (EvidenceItem item : items) ids.addAll(item.sources);
        return ids;
    }

    private static List<EvidenceItem> deduplicate(List<EvidenceItem> items) {
        LinkedHashMap<String, EvidenceItem> unique = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            String key = item.kind + "\n" + item.text;
            EvidenceItem existing = unique.get(key);
            if (existing == null) unique.put(key, new EvidenceItem(item.kind, item.text, new ArrayList<>(item.sources)));
            else {
                LinkedHashSet<String> merged = new LinkedHashSet<>(existing.sources);
                merged.addAll(item.sources);
                existing.sources = new ArrayList<>(merged);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String render(List<EvidenceItem> items, Map<String, Source> sources) {
        if (items.isEmpty()) return "No supported summary items were extracted. Review the original transcript.\n";
        StringBuilder result = new StringBuilder();
        String[] kinds = {"fact", "decision", "action", "open_question"};
        String[] headings = {"Summary", "Decisions", "Action items", "Open questions"};
        for (int i = 0; i < kinds.length; i++) {
            boolean heading = false;
            for (EvidenceItem item : items) {
                if (!kinds[i].equals(item.kind)) continue;
                if (!heading) { result.append("## ").append(headings[i]).append("\n\n"); heading = true; }
                result.append("- ").append(escapeMarkdown(item.text)).append(" [");
                for (int j = 0; j < item.sources.size(); j++) {
                    if (j > 0) result.append("; ");
                    result.append(sources.get(item.sources.get(j)).label());
                }
                result.append("]\n");
            }
            if (heading) result.append('\n');
        }
        return result.toString();
    }

    private static String escapeMarkdown(String text) {
        return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\r", " ").replace("\n", " ");
    }

    private static void atomicWrite(File destination, String contents) throws IOException {
        File temporary = File.createTempFile(destination.getName(), ".tmp", destination.getParentFile());
        try {
            Files.write(temporary.toPath(), contents.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte part : digest) hex.append(String.format(Locale.ROOT, "%02x", part & 255));
            return hex.toString();
        } catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
}
