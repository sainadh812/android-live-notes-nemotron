package check;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public class Gate {
    public static final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public static final CountDownLatch readEntered = new CountDownLatch(1), readRelease = new CountDownLatch(1);
    public static volatile boolean blockEmptyRead = false;
    public static final AtomicInteger feedFrames = new AtomicInteger();
    public static final java.util.List<Integer> feedSizes = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static final AtomicInteger inits = new AtomicInteger(), feeds = new AtomicInteger(),
        restarts = new AtomicInteger(), finalizes = new AtomicInteger(), destroys = new AtomicInteger();
    public static volatile String blockOperation = "", failOperation = "", audioFailure = "";
    private static final AtomicInteger active = new AtomicInteger();
    public static volatile boolean concurrentNativeCalls = false;
    private static void operation(String name, AtomicInteger count) {
        if (active.incrementAndGet() != 1) concurrentNativeCalls = true;
        count.incrementAndGet();
        try {
            if (blockOperation.equals(name)) {
                entered.countDown();
                release.await();
            }
            if (failOperation.equals(name)) throw new IllegalStateException("native " + name + " failed: test status");
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        } finally { active.decrementAndGet(); }
    }
    public static long init() { operation("init", inits); return 123; }
    public static String feed(float[] pcm) {
        for (float sample : pcm) if (sample != 0.5f) throw new AssertionError("PCM conversion changed");
        feedFrames.addAndGet(pcm.length);
        feedSizes.add(pcm.length);
        operation("feed", feeds);
        return "\u0001tentative";
    }
    public static boolean restart() { operation("restart", restarts); return true; }
    public static String finish() { operation("finalize", finalizes); return "final transcript"; }
    public static void destroy() { operation("destroy", destroys); }
}
