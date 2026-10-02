import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sainadh.livenotes.gemmaprototype.core.Cancellation;
import com.sainadh.livenotes.gemmaprototype.core.SummaryPipeline;
import com.sainadh.livenotes.gemmaprototype.core.TextEngine;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Runs the actual Android pipeline on a host JVM; Python supplies real inference. */
public final class JavaBenchmarkBridge {
    public static void main(String[] args) throws Exception {
        Gson json = new Gson();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        SummaryPipeline.Options options = new SummaryPipeline.Options();
        options.modelFingerprint = args[3];
        options.runtimeIdentity = "litert-lm-api-0.17.1-linux-cpu-real-inference";
        options.contextWindowTokens = Integer.parseInt(args[4]);
        options.maxOutputTokens = Integer.parseInt(args[5]);
        options.promptTemplateReserveTokens = Integer.parseInt(args[6]);
        options.thinking = Boolean.parseBoolean(args[2]);
        TextEngine engine = new TextEngine() {
            public int estimateTokenUpperBound(String text) {
                return text.getBytes(StandardCharsets.UTF_8).length;
            }
            public String generate(String system, String user, boolean thinking, int maxOutput, Cancellation cancellation) throws Exception {
                JsonObject request = new JsonObject();
                request.addProperty("type", "generate");
                request.addProperty("system", system);
                request.addProperty("user", user);
                request.addProperty("thinking", thinking);
                request.addProperty("maxOutputTokens", maxOutput);
                System.out.println(json.toJson(request));
                System.out.flush();
                String line = input.readLine();
                if (line == null) throw new IllegalStateException("Inference worker closed unexpectedly");
                JsonObject reply = JsonParser.parseString(line).getAsJsonObject();
                if (reply.has("error")) throw new IllegalStateException(reply.get("error").getAsString());
                return reply.get("text").getAsString();
            }
        };
        String transcript = Files.readString(new File(args[0]).toPath(), StandardCharsets.UTF_8);
        SummaryPipeline.Result result = new SummaryPipeline().run(transcript, new File(args[1]), options,
                engine, () -> false, (stage, completed, total, cached) -> {
                    JsonObject progress = new JsonObject();
                    progress.addProperty("type", "progress");
                    progress.addProperty("stage", stage);
                    progress.addProperty("completed", completed);
                    progress.addProperty("total", total);
                    progress.addProperty("cached", cached);
                    System.out.println(json.toJson(progress));
                    System.out.flush();
                });
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "result");
        reply.add("result", json.toJsonTree(result));
        System.out.println(json.toJson(reply));
        System.out.flush();
    }
}
