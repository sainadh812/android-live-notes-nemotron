package android.os
interface IBinder
object Build {
    object VERSION { const val SDK_INT = 35 }
    object VERSION_CODES { const val O = 26 }
}
class PowerManager {
    class WakeLock {
        var isHeld = false
        fun acquire() { isHeld = true }
        fun release() { isHeld = false }
    }
    fun newWakeLock(level: Int, tag: String) = WakeLock().also { locks += it }
    companion object {
        const val PARTIAL_WAKE_LOCK = 1
        val locks = mutableListOf<WakeLock>()
    }
}
