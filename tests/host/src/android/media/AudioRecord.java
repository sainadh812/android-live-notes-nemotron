package android.media;
import check.Gate;
import java.util.concurrent.atomic.AtomicInteger;
public class AudioRecord {
    public static final int STATE_INITIALIZED = 1, RECORDSTATE_RECORDING = 3, READ_NON_BLOCKING = 1;
    public static final AtomicInteger starts = new AtomicInteger(), releases = new AtomicInteger(),
        reads = new AtomicInteger(), readFrames = new AtomicInteger();
    public static final AtomicInteger availableFrames = new AtomicInteger(8000);
    private boolean recording;
    private Thread owner;
    public AudioRecord(int source, int rate, int channels, int format, int buffer) {
        owner = Thread.currentThread();
    }
    private void assertOwner() {
        if (Thread.currentThread() != owner) throw new AssertionError("AudioRecord owners overlapped");
    }
    public int getState() { assertOwner(); return Gate.audioFailure.equals("init") ? 0 : STATE_INITIALIZED; }
    public int getRecordingState() { assertOwner(); return recording ? RECORDSTATE_RECORDING : 1; }
    public static int getMinBufferSize(int rate, int channels, int format) { return 1024; }
    public void startRecording() {
        assertOwner();
        if (Gate.audioFailure.equals("start")) throw new IllegalStateException("Microphone start failed");
        recording = true;
        if (starts.getAndIncrement() > 0) availableFrames.set(8000);
    }
    public int read(short[] pcm, int offset, int length, int mode) {
        assertOwner();
        reads.incrementAndGet();
        if (mode != READ_NON_BLOCKING) throw new AssertionError("Blocking microphone read");
        if (Gate.audioFailure.equals("read") || (Gate.audioFailure.equals("read-after-tail") && readFrames.get() >= 1000)) return -6;
        if (!recording) return 0;
        int count = availableFrames.getAndUpdate(frames -> Math.max(0, frames - length));
        count = Math.min(count, length);
        if (count == 0 && Gate.blockEmptyRead) {
            Gate.readEntered.countDown();
            try { Gate.readRelease.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
        }
        for (int i = offset; i < offset + count; i++) pcm[i] = 16384;
        readFrames.addAndGet(count);
        return count;
    }
    public void stop() { assertOwner(); recording = false; }
    public void release() { assertOwner(); releases.incrementAndGet(); }
}
