package android.app
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.sainadh.livenotes.LiveNotesApplication
open class Application : Context()
open class Service : Context() {
    val application = LiveNotesApplication.instance
    var stopped = false
    open fun onCreate() = Unit
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = 0
    open fun onBind(intent: Intent?): IBinder? = null
    open fun onDestroy() = Unit
    fun startForeground(id: Int, notification: Notification) { NotificationManager.notify(id, notification) }
    fun stopForeground(flags: Int) { NotificationManager.active = false }
    fun stopSelfResult(startId: Int): Boolean { stopped = true; return true }
    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }
}
class Notification
class NotificationChannel(id: String, name: String, importance: Int) { var description = "" }
object NotificationManager {
    const val IMPORTANCE_LOW = 2
    var active = false
    var notifications = 0
    fun notify(id: Int, notification: Notification) { active = true; notifications += 1 }
    fun createNotificationChannel(channel: NotificationChannel) = Unit
}
class PendingIntent {
    companion object {
        const val FLAG_UPDATE_CURRENT = 1
        const val FLAG_IMMUTABLE = 2
        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int) = PendingIntent()
        fun getService(context: Context, requestCode: Int, intent: Intent, flags: Int) = PendingIntent()
    }
}
