package com.sainadh.livenotes.gemmaprototype;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import com.google.gson.GsonBuilder;
import com.sainadh.livenotes.gemmaprototype.core.*;
import com.sainadh.livenotes.gemmaprototype.runtime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns native work independently of Activity rotation. Checkpoints survive process death. */
public final class SummaryService extends Service {
    public static final String DOWNLOAD = "download", IMPORT = "import", RUN = "run", CANCEL = "cancel";
    private static final int NOTIFICATION = 41;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile String status = "Download Gemma, then import a transcript to start.";
    private static volatile String summary = "";
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile LiteRtTextEngine engine;
    private PowerManager.WakeLock wakeLock;
    private long lastNotification;

    public static boolean isBusy() { return BUSY.get(); }
    public static String getStatus() { return status; }
    public static String getSummary() { return summary; }
    public static File transcriptFile(Context c) { return new File(c.getFilesDir(), "transcript.txt"); }
    public static File reportFile(Context c) { return new File(c.getFilesDir(), "last-report.json"); }
    public static File summaryFile(Context c) { return new File(c.getFilesDir(), "last-summary.md"); }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("gemma", "Local summarization", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "GemmaPrototype:localSummary");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (CANCEL.equals(action)) {
            cancelled.set(true);
            LiteRtTextEngine active = engine;
            if (active != null) active.cancel();
            update("Stopping safely; completed sections are saved.");
            if (!BUSY.get()) stopSelf();
            return START_NOT_STICKY;
        }
        if (!BUSY.compareAndSet(false, true)) return START_NOT_STICKY;
        cancelled.set(false);
        startForeground(NOTIFICATION, notification("Preparing local task…"));
        wakeLock.acquire(4 * 60 * 60 * 1000L);
        worker.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("model", ModelSpec.NAME);
            report.put("modelSha256", ModelSpec.SHA256);
            report.put("runtime", ModelSpec.RUNTIME_VERSION);
            report.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            report.put("androidApi", Build.VERSION.SDK_INT);
            report.put("startedAt", new Date().toString());
            report.put("cloudInference", false);
            try {
                ModelStore store = new ModelStore(this);
                ModelStore.Progress progress = (phase, done, total) -> update(phase + " · " +
                    String.format(Locale.US, "%.0f%%", 100.0 * done / Math.max(1, total)));
                Cancellation cancel = cancelled::get;
                if (DOWNLOAD.equals(action)) {
                    store.download(cancel, progress);
                    update("Gemma is verified and ready. Import or paste a transcript.");
                } else if (IMPORT.equals(action)) {
                    store.importModel(Objects.requireNonNull(intent.getData()), cancel, progress);
                    update("Gemma is verified and ready.");
                } else if (RUN.equals(action)) {
                    String transcript = LocalFiles.read(transcriptFile(this));
                    if (transcript.trim().isEmpty()) throw new IOException("Import or paste a transcript first.");
                    summary = "";
                    LocalFiles.delete(summaryFile(this));
                    LocalFiles.delete(reportFile(this));
                    boolean thinking = intent.getBooleanExtra("thinking", false);
                    boolean cpu = intent.getBooleanExtra("cpu", false);
                    report.put("synthetic", intent.getBooleanExtra("synthetic", false));
                    report.put("reasoningForFinalSummary", thinking);
                    report.put("requestedBackend", cpu ? "CPU" : "GPU");
                    report.put("transcriptCharacters", transcript.length());
                    File model = store.requireVerifiedModel(cancel, progress);
                    engine = LiteRtTextEngine.load(model, new File(getCacheDir(), "litert"),
                        cpu ? LiteRtTextEngine.BackendPreference.CPU : LiteRtTextEngine.BackendPreference.GPU,
                        8192, true, cancel, this::update);
                    report.put("actualBackend", engine.getBackendName());
                    report.put("modelInitializationMillis", engine.getInitializationMillis());
                    SummaryPipeline.Options options = new SummaryPipeline.Options();
                    options.modelFingerprint = ModelSpec.SHA256;
                    options.runtimeIdentity = ModelSpec.RUNTIME_VERSION + ":" + engine.getBackendName();
                    options.contextWindowTokens = 8192;
                    options.maxOutputTokens = 2048;
                    options.thinking = thinking;
                    report.put("options", options);
                    SummaryPipeline.Result result = new SummaryPipeline().run(transcript,
                        new File(getFilesDir(), "checkpoints"), options, engine, cancel,
                        (stage, done, total, cached) -> update(stage + " " + done + "/" + total + (cached ? " · resumed" : "")));
                    summary = result.markdown;
                    LocalFiles.write(summaryFile(this), summary);
                    report.put("result", result);
                    report.put("status", "complete");
                    update("Complete · " + result.chunkCount + " sections · " + result.cacheHits +
                        " reused · " + ((SystemClock.elapsedRealtime() - started) / 1000) + " seconds");
                } else throw new IOException("Unknown operation.");
            } catch (CancellationException e) {
                report.put("status", "cancelled");
                update("Stopped. Start again with the same transcript and settings to resume.");
            } catch (Throwable e) {
                report.put("status", "failed");
                report.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                update("Could not finish: " + e.getMessage() + ". Completed sections are saved.");
            } finally {
                LiteRtTextEngine active = engine;
                if (active != null) {
                    report.put("generations", active.getGenerationStats());
                    try { active.close(); } catch (Exception ignored) { }
                    engine = null;
                }
                report.put("totalElapsedMillis", SystemClock.elapsedRealtime() - started);
                if (RUN.equals(action)) {
                    try { LocalFiles.write(reportFile(this), new GsonBuilder().setPrettyPrinting()
                        .create().toJson(report)); }
                    catch (Exception e) { update(status + " Report could not be saved."); }
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (wakeLock.isHeld()) wakeLock.release();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    BUSY.set(false);
                });
            }
        });
        return START_NOT_STICKY;
    }

    private void update(String message) {
        status = message;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotification > 1000) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
            lastNotification = now;
        }
    }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, SummaryService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "gemma").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Gemma · on this device").setContentText(message).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancel).build()).build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        cancelled.set(true);
        LiteRtTextEngine active = engine;
        if (active != null) active.cancel();
        worker.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
