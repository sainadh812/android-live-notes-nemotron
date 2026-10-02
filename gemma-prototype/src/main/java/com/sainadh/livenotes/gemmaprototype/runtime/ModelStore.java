package com.sainadh.livenotes.gemmaprototype.runtime;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import com.sainadh.livenotes.gemmaprototype.core.Cancellation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloaded models stay outside Android backup; only verified bytes can become the live model. */
public final class ModelStore {
    public interface Progress {
        void onProgress(String phase, long completedBytes, long totalBytes);
    }

    private final Context context;
    private final File directory;
    private final File model;
    private final File partial;
    private final File receipt;
    private final AtomicBoolean busy = new AtomicBoolean();

    public ModelStore(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getNoBackupFilesDir(), "gemma-model");
        model = new File(directory, ModelSpec.FILE_NAME);
        partial = new File(directory, ModelSpec.FILE_NAME + ".part");
        receipt = new File(directory, ModelSpec.FILE_NAME + ".verified");
    }

    public File getModelFile() { return model; }
    public long partialBytes() { return partial.isFile() ? partial.length() : 0L; }

    /** A quick UI check. Loading still performs a fresh full SHA-256 verification. */
    public boolean isReady() {
        try {
            return model.isFile() && model.length() == ModelSpec.BYTES && receipt.isFile()
                    && new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8)
                    .equals(receiptText());
        } catch (IOException ignored) {
            return false;
        }
    }

    public File requireVerifiedModel(Cancellation cancel, Progress progress) throws Exception {
        enter();
        try {
            verify(model, cancel, progress);
            writeReceipt();
            return model;
        } finally {
            busy.set(false);
        }
    }

    /** Resumes the pinned immutable download after cancellation or connection loss. */
    public File download(Cancellation cancel, Progress progress) throws Exception {
        enter();
        HttpURLConnection connection = null;
        try {
            ensureDirectory();
            cancel.throwIfCancelled();
            if (isReady()) {
                verify(model, cancel, progress);
                return model;
            }
            long offset = partialBytes();
            if (offset > ModelSpec.BYTES) {
                Files.delete(partial.toPath());
                offset = 0;
            }
            if (offset == ModelSpec.BYTES) {
                return finish(cancel, progress);
            }
            checkSpace(ModelSpec.BYTES - offset);
            connection = (HttpURLConnection) new URL(ModelSpec.DOWNLOAD_URL).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "LiveNotes-GemmaPrototype/1");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("Model download redirected away from HTTPS.");
            }
            if (status == HttpURLConnection.HTTP_PARTIAL) {
                String expectedRange = "bytes " + offset + "-" + (ModelSpec.BYTES - 1)
                        + "/" + ModelSpec.BYTES;
                if (!expectedRange.equals(connection.getHeaderField("Content-Range"))) {
                    throw new IOException("Unexpected model download range; partial data was preserved.");
                }
            } else if (status == HttpURLConnection.HTTP_OK) {
                // A server may ignore Range: safely replace the partial file, never append a full response.
                offset = 0;
                checkSpace(ModelSpec.BYTES - partialBytes());
            } else {
                throw new IOException("Model download failed (HTTP " + status + "). Retry to resume.");
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength >= 0 && declaredLength != ModelSpec.BYTES - offset) {
                throw new IOException("The server returned an unexpected model size.");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                copyToPartial(input, offset, cancel, progress, "Downloading model");
            }
            return finish(cancel, progress);
        } finally {
            if (connection != null) connection.disconnect();
            busy.set(false);
        }
    }

    /** SAF import accepts only the same pinned model; a wrong file never replaces a good model. */
    public File importModel(Uri uri, Cancellation cancel, Progress progress) throws Exception {
        enter();
        try {
            ensureDirectory();
            cancel.throwIfCancelled();
            checkSpace(ModelSpec.BYTES - partialBytes());
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open the selected model.");
                copyToPartial(new BufferedInputStream(input), 0, cancel, progress, "Importing model");
            }
            return finish(cancel, progress);
        } finally {
            busy.set(false);
        }
    }

    private void enter() {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("A model download, import or verification is already running.");
        }
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create model storage.");
        }
    }

    private void checkSpace(long additionalBytes) throws IOException {
        long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        long needed = Math.max(0, additionalBytes) + ModelSpec.DISK_RESERVE_BYTES;
        if (available < needed) {
            throw new IOException(String.format(Locale.US,
                    "Free at least %.1f GB before continuing (includes 3 GiB for runtime caches).",
                    needed / 1_000_000_000.0));
        }
    }

    private void copyToPartial(InputStream input, long offset, Cancellation cancel,
                               Progress progress, String phase) throws Exception {
        long written = offset;
        long lastReported = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (FileOutputStream fileOutput = new FileOutputStream(partial, offset > 0);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput, buffer.length)) {
            report(progress, phase, written);
            while (true) {
                cancel.throwIfCancelled();
                int count = input.read(buffer);
                if (count < 0) break;
                if (written + count > ModelSpec.BYTES) {
                    throw new IOException("Selected file is larger than the pinned Gemma model.");
                }
                output.write(buffer, 0, count);
                written += count;
                long now = System.nanoTime();
                if (now - lastReported >= 250_000_000L) {
                    report(progress, phase, written);
                    lastReported = now;
                }
            }
            output.flush();
            fileOutput.getFD().sync();
        }
        cancel.throwIfCancelled();
        if (written != ModelSpec.BYTES) {
            throw new IOException("Model is incomplete; retry Download to resume.");
        }
        report(progress, phase, written);
    }

    private File finish(Cancellation cancel, Progress progress) throws Exception {
        try {
            verify(partial, cancel, progress);
        } catch (IntegrityException failure) {
            // A corrupt complete file cannot be repaired by appending a Range response.
            Files.deleteIfExists(partial.toPath());
            throw failure;
        }
        cancel.throwIfCancelled();
        Files.move(partial.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        writeReceipt();
        report(progress, "Model ready", ModelSpec.BYTES);
        return model;
    }

    private void verify(File file, Cancellation cancel, Progress progress) throws Exception {
        if (!file.isFile() || file.length() != ModelSpec.BYTES) {
            throw new IntegrityException("Download or import the complete Gemma 4 E4B model first.");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long read = 0;
        long lastReported = 0;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            report(progress, "Verifying model SHA-256", 0);
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancel.throwIfCancelled();
                digest.update(buffer, 0, count);
                read += count;
                long now = System.nanoTime();
                if (now - lastReported >= 250_000_000L) {
                    report(progress, "Verifying model SHA-256", read);
                    lastReported = now;
                }
            }
        }
        cancel.throwIfCancelled();
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (read != ModelSpec.BYTES || !ModelSpec.SHA256.contentEquals(hex)) {
            throw new IntegrityException("Model checksum did not match. Download or import it again.");
        }
        report(progress, "Model verified", read);
    }

    private String receiptText() {
        return ModelSpec.SHA256 + "\n" + model.length() + "\n" + model.lastModified() + "\n";
    }

    private void writeReceipt() throws IOException {
        File staging = new File(directory, receipt.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(staging)) {
            output.write(receiptText().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Files.move(staging.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void report(Progress progress, String phase, long completed) {
        if (progress != null) progress.onProgress(phase, completed, ModelSpec.BYTES);
    }

    private static final class IntegrityException extends IOException {
        IntegrityException(String message) { super(message); }
    }
}
