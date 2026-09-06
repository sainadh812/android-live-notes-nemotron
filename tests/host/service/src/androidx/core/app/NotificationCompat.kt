package androidx.core.app
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
object NotificationCompat {
    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(id: Int) = this
        fun setContentTitle(title: String) = this
        fun setContentText(text: String) = this
        fun setContentIntent(intent: PendingIntent) = this
        fun setOngoing(ongoing: Boolean) = this
        fun addAction(icon: Int, title: String, intent: PendingIntent) = this
        fun build() = Notification()
    }
}
