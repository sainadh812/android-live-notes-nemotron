package android.os

class Looper {
    companion object {
        private val main = Looper()
        private val mainThread = Thread.currentThread()
        @JvmStatic fun getMainLooper() = main
        @JvmStatic fun myLooper(): Looper? = if (Thread.currentThread() === mainThread) main else null
    }
}

class Handler(looper: Looper) {
    fun post(runnable: Runnable) = postDelayed(runnable, 0)
    fun postDelayed(runnable: Runnable, delay: Long): Boolean {
        synchronized(tasks) { tasks += (now + delay) to runnable }
        return true
    }
    fun removeCallbacks(runnable: Runnable) {
        synchronized(tasks) { tasks.removeAll { it.second === runnable } }
    }
    companion object {
        private var now = 0L
        private val tasks = mutableListOf<Pair<Long, Runnable>>()
        fun advance(delay: Long) {
            synchronized(tasks) { now += delay }
            while (true) {
                val task = synchronized(tasks) {
                    tasks.filter { it.first <= now }.minByOrNull { it.first }?.also(tasks::remove)
                } ?: break
                task.second.run()
            }
        }
        fun reset() { synchronized(tasks) { tasks.clear(); now = 0 } }
    }
}
class Bundle(private val text: String) {
    fun getStringArrayList(key: String) = arrayListOf(text)
}
