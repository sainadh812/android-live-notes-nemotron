package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public class Handler {
    private static final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable action) { pending.add(action); return true; }
    public static void drain() {
        Runnable action;
        while ((action = pending.poll()) != null) action.run();
    }
}
