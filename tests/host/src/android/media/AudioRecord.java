package android.media;
import check.Gate;
import java.util.concurrent.atomic.AtomicInteger;
public class AudioRecord {
    public static final int STATE_INITIALIZED = 1, RECORDSTATE_RECORDING = 3, READ_NON_BLOCKING = 1;
    public static final AtomicInteger starts = new AtomicInteger(), releases = new AtomicInteger();
    private boolean recording, readOnce;
    public AudioRecord(int source, int rate, int channels, int format, int buffer) {}
    public int getState() { return Gate.audioFailure.equals("init") ? 0 : STATE_INITIALIZED; }
    public int getRecordingState() { return recording ? RECORDSTATE_RECORDING : 1; }
    public static int getMinBufferSize(int rate, int channels, int format) { return 1024; }
    public void startRecording() {
        if (Gate.audioFailure.equals("start")) throw new IllegalStateException("Microphone start failed");
        recording = true;
        starts.incrementAndGet();
    }
    public int read(short[] pcm, int offset, int length, int mode) {
        if (mode != READ_NON_BLOCKING) throw new AssertionError("Blocking microphone read");
        if (Gate.audioFailure.equals("read")) return -6;
        if (readOnce || !recording) return 0;
        readOnce = true;
        for (int i = offset; i < offset + length; i++) pcm[i] = 16384;
        return length;
    }
    public void stop() { recording = false; }
    public void release() { releases.incrementAndGet(); }
}
