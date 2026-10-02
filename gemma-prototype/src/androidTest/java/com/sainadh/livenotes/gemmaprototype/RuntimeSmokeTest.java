package com.sainadh.livenotes.gemmaprototype;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.ai.edge.litertlm.NativeLibraryLoader;
import com.sainadh.livenotes.gemmaprototype.core.Cancellation;
import com.sainadh.livenotes.gemmaprototype.runtime.LiteRtTextEngine;
import com.sainadh.livenotes.gemmaprototype.runtime.ModelStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * No weights are downloaded by these tests. The first two checks are suitable for emulator CI.
 *
 * Optional real inference: provision the pinned model into this app's private
 * no_backup/gemma-model/gemma-4-E4B-it.litertlm, then pass its absolute path as
 * -e modelPath /data/user/0/com.sainadh.livenotes.gemmaprototype/no_backup/gemma-model/gemma-4-E4B-it.litertlm.
 * The argument must identify ModelStore's own file; this avoids a second 3.66 GB copy. Its full
 * SHA-256 is verified through ModelStore before native loading. No argument means a reported skip,
 * never a fake successful inference measurement.
 */
@RunWith(AndroidJUnit4.class)
public final class RuntimeSmokeTest {
    @Test public void prototypeLaunchesWithLocalSummaryControls() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.sainadh.livenotes.gemmaprototype", context.getPackageName());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View content = activity.getWindow().getDecorView();
                assertNotNull(findText(content, "Long meeting summaries"));
                assertTrue(findText(content, "Download / resume model") instanceof Button);
                assertTrue(findText(content, "Import transcript (.txt)") instanceof Button);
                assertTrue(findText(content, "Summarize / resume") instanceof Button);
                assertTrue(findText(content, "Stop and save progress") instanceof Button);
            });
        }
    }

    @Test public void bundledNativeRuntimeLoadsOnTheCurrent64BitAbi() {
        assertTrue("The prototype ships ARM64 and x86_64 native runtimes", Process.is64Bit());
        List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);
        assertTrue("Expected an ARM64 phone or x86_64 emulator: " + abis,
                abis.contains("arm64-v8a") || abis.contains("x86_64"));
        // Use the pinned AAR's own loader, ensuring native methods share its application classloader.
        // Both loading and an actual JNI call must succeed; no model is needed for this check.
        NativeLibraryLoader.INSTANCE.load();
        NativeLibraryLoader.INSTANCE.nativeCheckLoaded();
    }

    @Test public void optionalVerifiedGemmaProducesARealCpuSummary() throws Exception {
        String supplied = InstrumentationRegistry.getArguments().getString("modelPath", "");
        Assume.assumeTrue("Real inference skipped: provision the pinned model and pass modelPath.",
                !supplied.isEmpty());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelStore store = new ModelStore(context);
        assertEquals("Provision the model directly in ModelStore; the test never copies multi-GB weights.",
                store.getModelFile().getCanonicalFile(), new File(supplied).getCanonicalFile());
        File model = store.requireVerifiedModel(Cancellation.NONE, null);
        try (LiteRtTextEngine engine = LiteRtTextEngine.load(model,
                new File(context.getCacheDir(), "litert-instrumentation"),
                LiteRtTextEngine.BackendPreference.CPU, 2048, false, Cancellation.NONE, null)) {
            String summary = engine.generate(
                    "Summarize the meeting in one short English sentence. Include the current deadline "
                            + "and the person responsible. A later correction replaces an earlier deadline.",
                    "[00:00] Maya: Deployment was planned for Tuesday.\n"
                            + "[00:08] Maya: Correction: the deployment deadline is Thursday.\n"
                            + "[00:12] Omar: I am responsible for deployment.",
                    false, 256, Cancellation.NONE);
            assertFalse("A real model must return visible final text", summary.trim().isEmpty());
            String lower = summary.toLowerCase(Locale.ROOT);
            assertTrue("Corrected deadline missing from actual response: " + summary, lower.contains("thursday"));
            assertTrue("Action owner missing from actual response: " + summary, lower.contains("omar"));
            assertEquals("CPU", engine.getBackendName());
            assertEquals(1, engine.getGenerationStats().size());
            LiteRtTextEngine.GenerationStats stats = engine.getGenerationStats().get(0);
            assertTrue("Actual native prompt token count must be available", stats.inputTokens > 0);
            assertTrue("Actual native decode count must be available", stats.generatedTokens > 0);
        }
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = findText(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }
}
