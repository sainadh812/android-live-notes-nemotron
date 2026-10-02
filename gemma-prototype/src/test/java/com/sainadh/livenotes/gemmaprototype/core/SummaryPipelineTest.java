package com.sainadh.livenotes.gemmaprototype.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Deterministic orchestration tests. These do not measure Gemma's summary quality. */
public class SummaryPipelineTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static SummaryPipeline.Options options() {
        SummaryPipeline.Options options = new SummaryPipeline.Options();
        options.modelFingerprint = "synthetic-test-model-sha256";
        options.runtimeIdentity = "deterministic-test-double";
        options.contextWindowTokens = 3072;
        options.maxOutputTokens = 384;
        options.promptTemplateReserveTokens = 128;
        return options;
    }

    private static class FakeEngine implements TextEngine {
        private static final Pattern IDS = Pattern.compile("\\[(S\\d+)(?: \\| [^\\]]+)?\\]");
        final Set<String> seenSourceIds = new LinkedHashSet<>();
        final List<String> prompts = new ArrayList<>();
        final List<Integer> outputBudgets = new ArrayList<>();
        final List<Boolean> reasoning = new ArrayList<>();
        int generationCalls;
        int merges;
        int contextLimit = 3072;
        int templateReserve = 128;

        @Override public int estimateTokenUpperBound(String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }

        @Override public String generate(String system, String user, boolean thinking,
                                         int maxOutputTokens, Cancellation cancellation) throws Exception {
            cancellation.throwIfCancelled();
            assertTrue("Every call must reserve the full prompt, total output, and template budget",
                    estimateTokenUpperBound(system) + estimateTokenUpperBound(user)
                            + maxOutputTokens + templateReserve <= contextLimit);
            generationCalls++;
            prompts.add(user);
            outputBudgets.add(maxOutputTokens);
            reasoning.add(thinking);
            if (system.equals(SummaryPrompts.MAP)) {
                Matcher ids = IDS.matcher(user);
                String first = null;
                while (ids.find()) {
                    seenSourceIds.add(ids.group(1));
                    if (first == null) first = ids.group(1);
                }
                assertNotNull(first);
                return response("fact", "Synthetic extracted item from " + first, first);
            }
            JsonArray input = JsonParser.parseString(user.substring(user.indexOf('\n') + 1)).getAsJsonArray();
            JsonArray output = new JsonArray();
            if (system.equals(SummaryPrompts.MERGE)) {
                merges++;
                // Deliberately tiny output to exercise reduction; not a quality simulation.
                if (!input.isEmpty()) output.add(input.get(0));
            } else {
                for (JsonElement item : input) output.add(item);
            }
            JsonObject result = new JsonObject();
            result.add("items", output);
            return result.toString();
        }

        @Override public String budgetDescription() { return "Test double UTF-8 bound, not real model tokens"; }
    }

    private static String response(String kind, String text, String... ids) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind);
        item.addProperty("text", text);
        item.add("sources", new Gson().toJsonTree(ids));
        JsonArray items = new JsonArray();
        items.add(item);
        JsonObject result = new JsonObject();
        result.add("items", items);
        return result.toString();
    }

    private static String syntheticTranscript(int lines) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            result.append(String.format(java.util.Locale.ROOT, "[%02d:%02d:%02d] ", i / 360, (i / 6) % 60, (i % 6) * 10));
            result.append("Synthetic record ").append(i).append(" discusses the schedule, budget, owner and unresolved testing details.\n");
        }
        return result.toString();
    }

    @Test public void fullTwoHourFixtureReachesEverySourceAndRecursivelyMerges() throws Exception {
        // 720 timestamped ten-second entries = two hours; intentionally synthetic.
        String transcript = syntheticTranscript(720);
        FakeEngine engine = new FakeEngine();
        SummaryPipeline.Result result = new SummaryPipeline().run(transcript, temporary.newFolder(), options(), engine,
                Cancellation.NONE, null);
        assertTrue(result.chunkCount > 10);
        assertTrue("Reduction must occur before final generation", engine.merges > 0);
        assertEquals(result.sources.size(), engine.seenSourceIds.size());
        assertEquals("S0720", result.sources.get(result.sources.size() - 1).id);
        assertTrue(engine.seenSourceIds.contains("S0001"));
        assertTrue(engine.seenSourceIds.contains("S0720"));
        assertTrue(result.budgetDescription.contains("Test double"));
        assertTrue(new File(result.checkpointDirectory, "sources.json").isFile());
        assertTrue(new File(result.checkpointDirectory, "summary.md").isFile());
    }

    @Test public void longUnicodeSentenceSplitsWithoutDroppingCharactersOrSurrogateHalves() throws Exception {
        String transcript = "[00:10:00] " + "Milanković’s 中文 😀 " .repeat(900) + "tail evidence";
        FakeEngine engine = new FakeEngine();
        SummaryPipeline pipeline = new SummaryPipeline();
        List<SummaryPipeline.Source> sources = pipeline.splitSources(transcript, options(), engine, Cancellation.NONE);
        boolean[] covered = new boolean[transcript.length()];
        assertTrue(sources.size() > 2);
        for (SummaryPipeline.Source source : sources) {
            assertFalse(Character.isLowSurrogate(transcript.charAt(source.startOffset)));
            assertFalse(Character.isHighSurrogate(transcript.charAt(source.endOffset - 1)));
            for (int i = source.startOffset; i < source.endOffset; i++) covered[i] = true;
            assertEquals(transcript.substring(source.startOffset, source.endOffset).trim(), source.text);
        }
        for (int i = 0; i < transcript.length(); i++) {
            if (!Character.isWhitespace(transcript.charAt(i))) assertTrue("Missing original character at " + i, covered[i]);
        }
        List<List<SummaryPipeline.Source>> chunks = pipeline.chunkSources(sources, options(), engine, Cancellation.NONE);
        Set<String> ids = new HashSet<>();
        for (List<SummaryPipeline.Source> chunk : chunks) for (SummaryPipeline.Source source : chunk) ids.add(source.id);
        assertEquals(sources.size(), ids.size());
        assertTrue(sources.get(sources.size() - 1).text.endsWith("tail evidence"));
    }

    @Test public void overlapPreservesBoundarySourceAndStableIds() throws Exception {
        SummaryPipeline pipeline = new SummaryPipeline();
        FakeEngine engine = new FakeEngine();
        List<SummaryPipeline.Source> sources = pipeline.splitSources(syntheticTranscript(80), options(), engine, Cancellation.NONE);
        List<List<SummaryPipeline.Source>> chunks = pipeline.chunkSources(sources, options(), engine, Cancellation.NONE);
        assertTrue(chunks.size() > 1);
        for (int i = 1; i < chunks.size(); i++) {
            List<SummaryPipeline.Source> previous = chunks.get(i - 1);
            assertEquals(previous.get(previous.size() - 1).id, chunks.get(i).get(0).id);
        }
        List<SummaryPipeline.Source> repeated = pipeline.splitSources(syntheticTranscript(80), options(), engine, Cancellation.NONE);
        assertEquals(new Gson().toJson(sources), new Gson().toJson(repeated));
    }

    @Test public void cancellationKeepsCompletedChunkAndResumeUsesCheckpoint() throws Exception {
        File root = temporary.newFolder();
        AtomicBoolean cancelled = new AtomicBoolean();
        SummaryPipeline pipeline = new SummaryPipeline();
        FakeEngine first = new FakeEngine();
        try {
            pipeline.run(syntheticTranscript(100), root, options(), first, cancelled::get,
                    (stage, done, total, cached) -> cancelled.set(true));
            fail("Expected cancellation");
        } catch (CancellationException expected) { assertEquals(1, first.generationCalls); }
        FakeEngine resumed = new FakeEngine();
        SummaryPipeline.Result result = pipeline.run(syntheticTranscript(100), root, options(), resumed, Cancellation.NONE, null);
        assertEquals(1, result.cacheHits);
        assertFalse(resumed.seenSourceIds.contains("S0001"));
        FakeEngine repeated = new FakeEngine();
        SummaryPipeline.Result cached = pipeline.run(syntheticTranscript(100), root, options(), repeated, Cancellation.NONE, null);
        assertEquals(0, repeated.generationCalls);
        assertTrue(cached.cacheHits > 1);
        assertEquals(result.markdown, cached.markdown);
    }

    @Test public void cancelledInFlightGenerationCannotWriteSuccessfulCheckpoint() throws Exception {
        File root = temporary.newFolder();
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeEngine engine = new FakeEngine() {
            @Override public String generate(String system, String user, boolean thinking, int output, Cancellation signal) throws Exception {
                String value = super.generate(system, user, thinking, output, signal);
                cancelled.set(true);
                return value;
            }
        };
        try {
            new SummaryPipeline().run("One input sentence.", root, options(), engine, cancelled::get, null);
            fail("Expected cancellation");
        } catch (CancellationException expected) {
            File directory = root.listFiles()[0];
            assertEquals(0, directory.listFiles().length);
        }
    }

    @Test public void modelSettingsAndTranscriptChangesNeverReuseStaleCheckpoints() throws Exception {
        SummaryPipeline pipeline = new SummaryPipeline();
        File root = temporary.newFolder();
        SummaryPipeline.Options opts = options();
        SummaryPipeline.Result a = pipeline.run("Original source.", root, opts, new FakeEngine(), Cancellation.NONE, null);
        opts.modelFingerprint = "another-model";
        SummaryPipeline.Result b = pipeline.run("Original source.", root, opts, new FakeEngine(), Cancellation.NONE, null);
        opts.maxOutputTokens = 385;
        SummaryPipeline.Result c = pipeline.run("Original source.", root, opts, new FakeEngine(), Cancellation.NONE, null);
        SummaryPipeline.Result d = pipeline.run("Changed source.", root, opts, new FakeEngine(), Cancellation.NONE, null);
        assertEquals(4, new HashSet<>(Arrays.asList(a.checkpointDirectory, b.checkpointDirectory,
                c.checkpointDirectory, d.checkpointDirectory)).size());
        assertEquals(0, b.cacheHits + c.cacheHits + d.cacheHits);
    }

    @Test public void corruptCheckpointIsRegeneratedAndSourceIdChecksStillApply() throws Exception {
        File root = temporary.newFolder();
        SummaryPipeline pipeline = new SummaryPipeline();
        SummaryPipeline.Result first = pipeline.run("Original source.", root, options(), new FakeEngine(), Cancellation.NONE, null);
        File[] checkpoints = new File(first.checkpointDirectory).listFiles((dir, name) -> name.startsWith("extract-") && name.endsWith(".json"));
        assertEquals(1, checkpoints.length);
        JsonObject stored = JsonParser.parseString(new String(Files.readAllBytes(checkpoints[0].toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        stored.addProperty("response", response("fact", "Corrupt cached source citation", "S9999"));
        Files.write(checkpoints[0].toPath(), stored.toString().getBytes(StandardCharsets.UTF_8));
        FakeEngine regenerated = new FakeEngine();
        SummaryPipeline.Result result = pipeline.run("Original source.", root, options(), regenerated, Cancellation.NONE, null);
        assertEquals(1, regenerated.generationCalls);
        assertFalse(result.markdown.contains("S9999"));
    }

    @Test public void rejectsUnknownMissingAndMalformedSourceEvidence() {
        Set<String> allowed = Collections.singleton("S0001");
        for (String response : Arrays.asList(
                response("fact", "Invented source", "S9999"), response("fact", "No source"),
                response("unsupported_kind", "Claim", "S0001"), response("fact", " ", "S0001"),
                "{\"items\":[{\"kind\":\"fact\",\"text\":42,\"sources\":[\"S0001\"]}]}",
                "not JSON", "{\"items\":[] trailing}")) {
            try { SummaryPipeline.parseEvidence(response, allowed); fail("Should reject " + response); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("checks")); }
        }
    }

    @Test public void finalLabelsComeFromInputAndModelMarkdownCannotCreateCitationLinks() throws Exception {
        FakeEngine engine = new FakeEngine() {
            @Override public String generate(String system, String user, boolean thinking, int output, Cancellation cancellation) {
                return response("fact", "A statement [S9999 @ 90:00](https://example.test)", "S0001");
            }
        };
        SummaryPipeline.Result result = new SummaryPipeline().run("[00:12:34] Source text.", temporary.newFolder(),
                options(), engine, Cancellation.NONE, null);
        assertTrue(result.markdown.contains("[S0001 @ 00:12:34]"));
        assertTrue(result.markdown.contains("\\[S9999 @ 90:00\\]"));
        assertEquals("00:12:34", result.sources.get(0).timestamp);
    }

    @Test public void thinkingSharesTotalOutputBudgetAndDoesNotAddUnreservedTokens() throws Exception {
        SummaryPipeline.Options opts = options();
        opts.thinking = true;
        FakeEngine engine = new FakeEngine();
        new SummaryPipeline().run("One source.", temporary.newFolder(), opts, engine, Cancellation.NONE, null);
        assertFalse(engine.outputBudgets.isEmpty());
        for (int budget : engine.outputBudgets) assertEquals(opts.maxOutputTokens, budget);
        assertEquals(Arrays.asList(false, true), engine.reasoning);
    }

    @Test public void comparingFinalThinkingReusesExtractionButRegeneratesFinal() throws Exception {
        File root = temporary.newFolder();
        SummaryPipeline.Options opts = options();
        SummaryPipeline pipeline = new SummaryPipeline();
        SummaryPipeline.Result without = pipeline.run("One source.", root, opts, new FakeEngine(), Cancellation.NONE, null);
        opts.thinking = true;
        FakeEngine comparison = new FakeEngine();
        SummaryPipeline.Result with = pipeline.run("One source.", root, opts, comparison, Cancellation.NONE, null);
        assertEquals(without.checkpointDirectory, with.checkpointDirectory);
        assertEquals(1, with.cacheHits);
        assertEquals(1, comparison.generationCalls);
        assertEquals(Collections.singletonList(true), comparison.reasoning);
        assertTrue(with.finalThinking);
    }

    @Test public void invalidBudgetFailsBeforeGenerationWithoutDiscardingInput() throws Exception {
        SummaryPipeline.Options opts = options();
        opts.contextWindowTokens = 512;
        opts.maxOutputTokens = 128;
        opts.promptTemplateReserveTokens = 64;
        FakeEngine engine = new FakeEngine();
        try {
            new SummaryPipeline().run("Source text that cannot fit with the prompt.", temporary.newFolder(), opts,
                    engine, Cancellation.NONE, null);
            fail("Expected context failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Context"));
            assertEquals(0, engine.generationCalls);
        }
    }

    @Test public void emptyExtractionReturnsReviewMessageWithoutInventingFacts() throws Exception {
        FakeEngine engine = new FakeEngine() {
            @Override public String generate(String system, String user, boolean thinking, int output, Cancellation cancellation) {
                return "{\"items\":[]}";
            }
        };
        SummaryPipeline.Result result = new SummaryPipeline().run("Um, uh.", temporary.newFolder(), options(), engine,
                Cancellation.NONE, null);
        assertTrue(result.items.isEmpty());
        assertTrue(result.markdown.contains("Review the original transcript"));
        assertEquals(1, result.generatedCalls);
    }

    @Test public void nonReducingModelFailsExplicitlyInsteadOfLoopingOrDroppingEvidence() throws Exception {
        FakeEngine engine = new FakeEngine() {
            @Override public String generate(String system, String user, boolean thinking, int output, Cancellation cancellation) throws Exception {
                if (system.equals(SummaryPrompts.MERGE)) {
                    merges++;
                    JsonObject result = new JsonObject();
                    result.add("items", JsonParser.parseString(user.substring(user.indexOf('\n') + 1)));
                    return result.toString();
                }
                return super.generate(system, user, thinking, output, cancellation);
            }
        };
        File root = temporary.newFolder();
        try {
            new SummaryPipeline().run(syntheticTranscript(720), root, options(), engine, Cancellation.NONE, null);
            fail("An unchanged oversized evidence set cannot be used for final generation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("did not reduce"));
            assertTrue(expected.getMessage().contains("no transcript text was dropped"));
            assertTrue(engine.merges > 0);
            assertEquals(720, engine.seenSourceIds.size());
            assertFalse(new File(root.listFiles()[0], "summary.md").exists());
        }
    }

    @Test public void malformedJsonGetsOneBudgetedFullInputRetryAndOnlyValidResultIsCached() throws Exception {
        File root = temporary.newFolder();
        FakeEngine engine = new FakeEngine() {
            boolean malformedReturned;
            String originalUser;

            @Override public String generate(String system, String user, boolean thinking, int output, Cancellation cancellation) throws Exception {
                assertTrue("The repair reminder must fit with the full original input",
                        estimateTokenUpperBound(system) + estimateTokenUpperBound(user) + output + templateReserve <= contextLimit);
                String baseSystem = system.startsWith(SummaryPrompts.MAP) ? SummaryPrompts.MAP : system;
                String valid = super.generate(baseSystem, user, thinking, output, cancellation);
                if (!malformedReturned) {
                    malformedReturned = true;
                    originalUser = user;
                    return "{\"items\":[";
                }
                if (system.contains("FORMAT RETRY:")) assertEquals(originalUser, user);
                return valid;
            }
        };
        SummaryPipeline pipeline = new SummaryPipeline();
        SummaryPipeline.Result result = pipeline.run("[00:10:00] Complete source including its final detail.", root,
                options(), engine, Cancellation.NONE, null);
        assertEquals(3, result.generatedCalls); // extraction, format retry, final synthesis
        assertEquals(3, engine.generationCalls);
        assertFalse(result.items.isEmpty());
        FakeEngine reused = new FakeEngine();
        SummaryPipeline.Result cached = pipeline.run("[00:10:00] Complete source including its final detail.", root,
                options(), reused, Cancellation.NONE, null);
        assertEquals(0, reused.generationCalls);
        assertEquals(2, cached.cacheHits);
    }

    @Test public void repeatedInvalidJsonStopsAfterTwoAttemptsAndWritesNoSuccessCheckpoint() throws Exception {
        File root = temporary.newFolder();
        FakeEngine engine = new FakeEngine() {
            @Override public String generate(String system, String user, boolean thinking, int output, Cancellation cancellation) {
                generationCalls++;
                return "{\"items\":[";
            }
        };
        try {
            new SummaryPipeline().run("Complete source.", root, options(), engine, Cancellation.NONE, null);
            fail("A repeatedly malformed response must fail");
        } catch (IllegalArgumentException expected) {
            assertEquals(2, engine.generationCalls);
            assertEquals(0, root.listFiles()[0].listFiles().length);
        }
    }
}
